package com.noopi.game.mafia;

import com.noopi.game.session.GameSessionRuntime;
import com.noopi.game.session.GameSessionRuntime.Participant;
import com.noopi.realtime.RoomEvents;
import com.noopi.room.PlayerRuntime;
import com.noopi.room.RoomRuntime;
import java.util.*;
import java.util.random.RandomGenerator;
import org.springframework.stereotype.Service;
import static com.noopi.api.ErrorCode.*;
import static com.noopi.game.mafia.MafiaGameRuntime.Phase.*;

/** All methods run inside the room lock supplied by RoomStore. */
@Service
public class MafiaGameService {
    private final RandomGenerator random;
    private final RoomEvents events;
    public MafiaGameService(RandomGenerator random, RoomEvents events) { this.random = random; this.events = events; }
    public MafiaGameRuntime prepare() { return new MafiaGameRuntime(); }
    private MafiaGameRuntime game(RoomRuntime room) { return (MafiaGameRuntime) room.session.game; }

    public Map<String, Integer> composition(int count) {
        int mafia = count >= 12 ? 3 : count >= 7 ? 2 : 1;
        int police = 1;
        int doctor = count >= 5 ? 1 : 0;
        return Map.of("mafia", mafia, "police", police, "doctor", doctor,
            "citizen", Math.max(0, count - mafia - police - doctor));
    }
    public void start(RoomRuntime room) {
        GAME_SESSION_NOT_READY.require(room.session.status == GameSessionRuntime.Status.READY);
        var players = room.players.values().stream().filter(p -> p.connectionStatus == PlayerRuntime.ConnectionStatus.CONNECTED)
            .map(p -> new Participant(p.id, p.nickname)).toList();
        NOT_ENOUGH_PLAYERS.require(players.size() >= 4);
        TOO_MANY_PLAYERS.require(players.size() <= 12);
        var counts = composition(players.size());
        var roles = new ArrayList<MafiaGameRuntime.Role>();
        add(roles, MafiaGameRuntime.Role.MAFIA, counts.get("mafia"));
        add(roles, MafiaGameRuntime.Role.POLICE, counts.get("police"));
        add(roles, MafiaGameRuntime.Role.DOCTOR, counts.get("doctor"));
        add(roles, MafiaGameRuntime.Role.CITIZEN, counts.get("citizen"));
        Collections.shuffle(roles, new Random(random.nextLong()));
        room.session.start(players);
        var g = game(room);
        for (int i = 0; i < players.size(); i++) {
            g.roles.put(players.get(i).playerId(), roles.get(i));
            g.alive.add(players.get(i).playerId());
        }
        g.phase = ROLE_REVEAL;
        events.game(room, "GAME_STARTED", Map.of("gameType", "MAFIA"));
    }
    private static void add(List<MafiaGameRuntime.Role> roles, MafiaGameRuntime.Role role, int count) {
        for (int i = 0; i < count; i++) roles.add(role);
    }
    public void roleCheck(RoomRuntime room, long playerId) {
        room.session.requireParticipant(playerId);
        var g = game(room);
        INVALID_GAME_PHASE.require(g.phase == ROLE_REVEAL);
        ROLE_ALREADY_CHECKED.require(g.checked.add(playerId));
        events.game(room, "MAFIA_ROLE_CHECKED", Map.of("roleCheckedCount", g.checked.size(),
            "participantCount", room.session.activeIds().size()));
        if (g.checked.containsAll(room.session.activeIds())) beginNight(room, true);
    }
    private void beginNight(RoomRuntime room, boolean first) {
        var g = game(room);
        g.nightNo++;
        g.nightActions.clear();
        g.phase = first ? FIRST_NIGHT : NIGHT;
        events.game(room, "MAFIA_PHASE_CHANGED", Map.of("phase", g.phase.name()));
    }
    public Map<String, Object> nightAction(RoomRuntime room, long playerId, String rawType, Long target) {
        room.session.requireParticipant(playerId);
        var g = game(room);
        PLAYER_DEAD.require(g.alive.contains(playerId));
        INVALID_GAME_PHASE.require(g.phase == FIRST_NIGHT || g.phase == NIGHT);
        ACTION_ALREADY_SUBMITTED.require(!g.nightActions.containsKey(playerId));
        MafiaGameRuntime.ActionType type;
        try { type = MafiaGameRuntime.ActionType.valueOf(Objects.toString(rawType, "")); }
        catch (IllegalArgumentException ex) { throw INVALID_NIGHT_ACTION.exception(); }
        var expected = expected(g, playerId);
        INVALID_NIGHT_ACTION.require(type == expected);
        if (type == MafiaGameRuntime.ActionType.CONFIRM) INVALID_ACTION_TARGET.require(target == null);
        else INVALID_ACTION_TARGET.require(target != null && eligible(g, playerId).contains(target));
        g.nightActions.put(playerId, new MafiaGameRuntime.NightAction(type, target));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("actionType", type.name());
        if (type == MafiaGameRuntime.ActionType.INVESTIGATE) {
            boolean mafia = g.roles.get(target) == MafiaGameRuntime.Role.MAFIA;
            var p = room.session.participants().get(target);
            g.investigations.computeIfAbsent(playerId, ignored -> new ArrayList<>())
                .add(new MafiaGameRuntime.Investigation(g.nightNo, target, p.nickname(), mafia));
            response.put("result", Map.of("targetPlayerId", target, "mafia", mafia));
        }
        events.game(room, "MAFIA_NIGHT_ACTION_SUBMITTED", Map.of("playerId", playerId, "nightNo", g.nightNo,
            "completedActionCount", g.nightActions.size(), "requiredActionCount", g.alive.size()));
        finishNightIfReady(room);
        return response;
    }
    MafiaGameRuntime.ActionType expected(MafiaGameRuntime g, long playerId) {
        var role = g.roles.get(playerId);
        if (g.phase == FIRST_NIGHT && (role == MafiaGameRuntime.Role.MAFIA || role == MafiaGameRuntime.Role.DOCTOR))
            return MafiaGameRuntime.ActionType.CONFIRM;
        return switch (role) {
            case MAFIA -> MafiaGameRuntime.ActionType.ATTACK;
            case POLICE -> MafiaGameRuntime.ActionType.INVESTIGATE;
            case DOCTOR -> MafiaGameRuntime.ActionType.HEAL;
            case CITIZEN -> MafiaGameRuntime.ActionType.SUSPECT;
        };
    }
    Set<Long> eligible(MafiaGameRuntime g, long playerId) {
        var role = g.roles.get(playerId);
        var result = new LinkedHashSet<>(g.alive);
        if (role != MafiaGameRuntime.Role.DOCTOR) result.remove(playerId);
        if (role == MafiaGameRuntime.Role.MAFIA) result.removeIf(id -> g.roles.get(id) == MafiaGameRuntime.Role.MAFIA);
        if (role == MafiaGameRuntime.Role.DOCTOR && g.previousHealTarget != null) result.remove(g.previousHealTarget);
        return result;
    }
    private void finishNightIfReady(RoomRuntime room) {
        var g = game(room);
        if (g.nightActions.size() != g.alive.size()) return;
        var suspicion = new HashMap<Long, Integer>();
        g.nightActions.values().stream().filter(a -> a.type() == MafiaGameRuntime.ActionType.SUSPECT)
            .forEach(a -> suspicion.merge(a.targetId(), 1, Integer::sum));
        if (g.phase == FIRST_NIGHT) {
            g.lastNightResult = new MafiaGameRuntime.NightResult(g.nightNo, null, Map.copyOf(suspicion));
            g.dayNo = 1; g.phase = DAY;
            events.game(room, "MAFIA_PHASE_CHANGED", Map.of("phase", "DAY"));
            return;
        }
        Long attack = plurality(g.nightActions.values().stream().filter(a -> a.type() == MafiaGameRuntime.ActionType.ATTACK)
            .map(MafiaGameRuntime.NightAction::targetId).toList());
        Long heal = g.nightActions.values().stream().filter(a -> a.type() == MafiaGameRuntime.ActionType.HEAL)
            .map(MafiaGameRuntime.NightAction::targetId).findFirst().orElse(null);
        g.previousHealTarget = heal;
        MafiaGameRuntime.Death death = null;
        if (attack != null && !Objects.equals(attack, heal)) death = kill(room, attack, "NIGHT_ATTACK");
        g.lastNightResult = new MafiaGameRuntime.NightResult(g.nightNo, death, Map.copyOf(suspicion));
        if (!finishIfWon(room)) {
            g.phase = NIGHT_RESULT;
            events.game(room, "MAFIA_PHASE_CHANGED", Map.of("phase", "NIGHT_RESULT"));
        }
    }
    private Long plurality(List<Long> targets) {
        if (targets.isEmpty()) return null;
        var counts = new HashMap<Long, Integer>(); targets.forEach(id -> counts.merge(id, 1, Integer::sum));
        int max = counts.values().stream().mapToInt(Integer::intValue).max().orElseThrow();
        var tied = counts.entrySet().stream().filter(e -> e.getValue() == max).map(Map.Entry::getKey).toList();
        return tied.get(random.nextInt(tied.size()));
    }
    public long startVote(RoomRuntime room, long playerId) {
        room.session.requireParticipant(playerId);
        var g = game(room);
        INVALID_GAME_PHASE.require(g.phase == DAY);
        VOTE_ALREADY_STARTED.require(g.voteRound == 0 || g.previousVoteResult != null);
        g.voteRound = 1; g.candidates = new LinkedHashSet<>(g.alive); g.votes.clear(); g.previousVoteResult = null; g.phase = VOTING;
        events.game(room, "MAFIA_VOTE_STARTED", Map.of("voteRound", 1, "requiredVoteCount", g.alive.size()));
        return 1;
    }
    public void vote(RoomRuntime room, long playerId, Long round, Long target) {
        room.session.requireParticipant(playerId); var g = game(room);
        PLAYER_DEAD.require(g.alive.contains(playerId)); INVALID_GAME_PHASE.require(g.voting());
        INVALID_VOTE_ROUND.require(round != null && round == g.voteRound);
        ALREADY_VOTED.require(!g.votes.containsKey(playerId)); CANNOT_VOTE_SELF.require(target == null || target != playerId);
        INVALID_VOTE_TARGET.require(target != null && g.alive.contains(target) && g.candidates.contains(target));
        g.votes.put(playerId, target);
        events.game(room, "MAFIA_PLAYER_VOTED", Map.of("voteRound", g.voteRound, "completedVoteCount", g.votes.size(), "requiredVoteCount", g.alive.size()));
        tallyIfReady(room);
    }
    private void tallyIfReady(RoomRuntime room) {
        var g = game(room); if (g.votes.size() != g.alive.size()) return;
        var counts = g.candidates.stream().map(id -> new MafiaGameRuntime.Count(id, room.session.participants().get(id).nickname(),
            (int) g.votes.values().stream().filter(id::equals).count())).toList();
        int max = counts.stream().mapToInt(MafiaGameRuntime.Count::voteCount).max().orElseThrow();
        var leaders = counts.stream().filter(c -> c.voteCount() == max).map(MafiaGameRuntime.Count::playerId).toList();
        boolean tied = leaders.size() > 1; g.accused = tied ? null : leaders.getFirst();
        g.previousVoteResult = new MafiaGameRuntime.VoteResult(g.voteRound, tied, counts, g.accused); g.votes.clear();
        Map<String, Object> payload = new LinkedHashMap<>(); payload.put("voteRound", g.voteRound); payload.put("tied", tied);
        payload.put("counts", counts.stream().map(c -> Map.of("playerId", c.playerId(), "voteCount", c.voteCount())).toList());
        if (!tied) payload.put("executionTargetPlayerId", g.accused);
        events.game(room, "MAFIA_VOTE_RESULT", payload);
        if (tied) {
            g.voteRound++; g.candidates = new LinkedHashSet<>(leaders); g.phase = REVOTING;
            events.game(room, "MAFIA_REVOTE_STARTED", Map.of("voteRound", g.voteRound, "candidatePlayerIds", leaders, "requiredVoteCount", g.alive.size()));
        } else g.phase = VOTE_RESULT;
    }
    public void judgment(RoomRuntime room, long playerId, String rawChoice) {
        room.session.requireParticipant(playerId); var g = game(room);
        PLAYER_DEAD.require(g.alive.contains(playerId)); INVALID_GAME_PHASE.require(g.phase == JUDGMENT);
        INVALID_VOTE_TARGET.require(!Objects.equals(g.accused, playerId)); ALREADY_VOTED.require(!g.judgmentVotes.containsKey(playerId));
        MafiaGameRuntime.JudgmentChoice choice;
        try { choice = MafiaGameRuntime.JudgmentChoice.valueOf(Objects.toString(rawChoice, "")); }
        catch (IllegalArgumentException ex) { throw INVALID_VOTE_TARGET.exception(); }
        g.judgmentVotes.put(playerId, choice);
        events.game(room, "MAFIA_JUDGMENT_VOTED", judgmentProgress(g));
        if (g.judgmentVotes.size() == g.alive.size() - 1) {
            g.executed = executeCount(g) > saveCount(g); g.phase = JUDGMENT_RESULT;
            events.game(room, "MAFIA_PHASE_CHANGED", Map.of("phase", "JUDGMENT_RESULT"));
        }
    }
    public void advance(RoomRuntime room, long playerId) {
        room.session.requireParticipant(playerId); var g = game(room);
        switch (g.phase) {
            case VOTE_RESULT -> { g.judgmentVotes.clear(); g.phase = JUDGMENT; }
            case JUDGMENT_RESULT -> {
                if (!g.executed) { beginNight(room, false); return; }
                g.executionResult = kill(room, g.accused, "EXECUTION");
                if (finishIfWon(room)) return;
                g.phase = EXECUTION;
            }
            case EXECUTION -> { beginNight(room, false); return; }
            case NIGHT_RESULT -> { g.dayNo++; g.voteRound = 0; g.previousVoteResult = null; g.phase = DAY; }
            default -> throw INVALID_GAME_PHASE.exception();
        }
        events.game(room, "MAFIA_PHASE_CHANGED", Map.of("phase", g.phase.name()));
    }
    private MafiaGameRuntime.Death kill(RoomRuntime room, long id, String cause) {
        var g = game(room); g.alive.remove(id); var p = room.session.participants().get(id);
        g.revealedRoles.put(id, g.roles.get(id));
        var death = new MafiaGameRuntime.Death(id, p.nickname(), g.roles.get(id));
        events.game(room, "MAFIA_PLAYER_DIED", Map.of("playerId", id, "revealedRole", death.revealedRole().name(), "cause", cause));
        return death;
    }
    private boolean finishIfWon(RoomRuntime room) {
        var g = game(room);
        long mafia = g.alive.stream().filter(id -> g.roles.get(id) == MafiaGameRuntime.Role.MAFIA).count();
        long citizen = g.alive.size() - mafia;
        if (mafia > 0 && mafia < citizen) return false;
        g.winnerTeam = mafia == 0 ? "CITIZEN_TEAM" : "MAFIA_TEAM"; g.phase = FINISHED;
        room.session.status = GameSessionRuntime.Status.FINISHED; events.game(room, "GAME_FINISHED", Map.of()); return true;
    }
    public void cancel(RoomRuntime room, String reason) {
        GAME_SESSION_ALREADY_FINISHED.require(!room.session.ended()); var g = game(room); g.phase = CANCELLED;
        g.votes.clear(); g.nightActions.clear(); g.judgmentVotes.clear(); room.session.status = GameSessionRuntime.Status.CANCELLED;
        events.game(room, "GAME_CANCELLED", Map.of("reason", reason));
    }
    int executeCount(MafiaGameRuntime g) { return (int) g.judgmentVotes.values().stream().filter(v -> v == MafiaGameRuntime.JudgmentChoice.EXECUTE).count(); }
    int saveCount(MafiaGameRuntime g) { return (int) g.judgmentVotes.values().stream().filter(v -> v == MafiaGameRuntime.JudgmentChoice.SAVE).count(); }
    Map<String, Object> judgmentProgress(MafiaGameRuntime g) { return Map.of("executeCount", executeCount(g), "saveCount", saveCount(g),
        "completedVoteCount", g.judgmentVotes.size(), "requiredVoteCount", g.alive.size() - 1); }
}
