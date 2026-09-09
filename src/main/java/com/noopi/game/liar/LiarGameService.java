package com.noopi.game.liar;

import com.noopi.content.LiarContent;
import com.noopi.game.session.GameSessionRuntime;
import com.noopi.game.session.GameSessionRuntime.Participant;
import com.noopi.realtime.RoomEvents;
import com.noopi.room.PlayerRuntime;
import com.noopi.room.RoomRuntime;
import java.util.*;
import java.util.random.RandomGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import static com.noopi.api.ErrorCode.*;
import static com.noopi.game.liar.LiarGameRuntime.Phase.*;

/** Invoked only inside RoomStore.inRoom: validation, mutation and public event ordering are atomic. */
@Service
public class LiarGameService {
    private final LiarContent content;
    private final RandomGenerator random;
    private final RoomEvents events;
    private final int recentLimit;
    public LiarGameService(LiarContent content, RandomGenerator random, RoomEvents events,
                           @Value("${noopi.recent-keyword-count:10}") int recentLimit) {
        this.content = content; this.random = random; this.events = events; this.recentLimit = Math.max(0, recentLimit);
    }
    public LiarGameRuntime prepare(String category) {
        var selected = content.categories().stream()
            .filter(candidate -> candidate.code().equals(category))
            .findFirst()
            .orElseThrow(INVALID_CATEGORY::exception);
        return new LiarGameRuntime(selected.code(), selected.name());
    }
    private LiarGameRuntime game(RoomRuntime room) { return (LiarGameRuntime) room.session.game; }

    public void start(RoomRuntime room) {
        var session = room.session;
        GAME_SESSION_NOT_READY.require(session.status == GameSessionRuntime.Status.READY);
        var players = room.players.values().stream()
            .filter(p -> p.connectionStatus == PlayerRuntime.ConnectionStatus.CONNECTED)
            .map(p -> new Participant(p.id, p.nickname)).toList();
        NOT_ENOUGH_PLAYERS.require(players.size() >= 3);
        TOO_MANY_PLAYERS.require(players.size() <= 12);
        var g = game(room);
        var recent = room.recentContent.getOrDefault("LIAR", new ArrayDeque<>());
        var keyword = content.choose(g.categoryCode, recent);
        long liar = players.get(random.nextInt(players.size())).playerId();
        session.start(players);
        g.keyword = keyword;
        g.liarId = liar;
        g.phase = ROLE_REVEAL;
        recent.addLast(keyword.id());
        while (recent.size() > recentLimit) recent.removeFirst();
        room.recentContent.put("LIAR", recent);
        events.game(room, "GAME_STARTED", Map.of("gameType", "LIAR"));
    }

    public void roleCheck(RoomRuntime room, long playerId) {
        room.session.requireParticipant(playerId);
        var g = game(room);
        ROLE_ALREADY_CHECKED.require(!g.checked.contains(playerId));
        INVALID_GAME_PHASE.require(g.phase == ROLE_REVEAL);
        g.checked.add(playerId);
        events.game(room, "ROLE_CHECKED", Map.of("playerId", playerId, "roleCheckedCount", g.checked.size(),
            "participantCount", room.session.activeIds().size()));
        beginDiscussionIfReady(room);
    }
    private void beginDiscussionIfReady(RoomRuntime room) {
        var g = game(room);
        var active = room.session.activeIds();
        if (g.phase == ROLE_REVEAL && !active.isEmpty() && g.checked.containsAll(active)) {
            g.firstSpeaker = active.get(random.nextInt(active.size()));
            g.phase = DISCUSSION;
            events.game(room, "DISCUSSION_STARTED", Map.of("firstSpeakerPlayerId", g.firstSpeaker));
        }
    }
    public long startVote(RoomRuntime room, long playerId) {
        room.session.requireParticipant(playerId);
        var g = game(room);
        VOTE_ALREADY_STARTED.require(g.voteRound == 0);
        INVALID_GAME_PHASE.require(g.phase == DISCUSSION);
        g.voteRound = 1;
        g.candidates = new LinkedHashSet<>(room.session.activeIds());
        g.phase = VOTING;
        events.game(room, "VOTE_STARTED", Map.of("voteRound", g.voteRound));
        return g.voteRound;
    }
    public void vote(RoomRuntime room, long playerId, Long round, Long target) {
        room.session.requireParticipant(playerId);
        var g = game(room);
        INVALID_GAME_PHASE.require(g.voting());
        INVALID_VOTE_ROUND.require(round != null && round == g.voteRound);
        ALREADY_VOTED.require(!g.votes.containsKey(playerId));
        CANNOT_VOTE_SELF.require(target == null || target != playerId);
        INVALID_VOTE_TARGET.require(target != null && g.candidates.contains(target));
        g.votes.put(playerId, target);
        events.game(room, "PLAYER_VOTED", Map.of("playerId", playerId, "voteRound", g.voteRound,
            "completedVoteCount", g.votes.size(), "requiredVoteCount", room.session.activeIds().size()));
        tallyIfComplete(room);
    }
    private void tallyIfComplete(RoomRuntime room) {
        var g = game(room);
        if (!g.voting() || g.votes.size() != room.session.activeIds().size() || g.votes.isEmpty()) return;
        var counts = g.candidates.stream().map(id -> new LiarGameRuntime.Count(id,
            room.session.participants().get(id).nickname(), (int) g.votes.values().stream().filter(id::equals).count())).toList();
        int max = counts.stream().mapToInt(LiarGameRuntime.Count::voteCount).max().orElseThrow();
        List<Long> leaders = counts.stream().filter(c -> c.voteCount() == max).map(LiarGameRuntime.Count::playerId).toList();
        boolean tied = leaders.size() > 1;
        g.phase = VOTE_RESULT;
        g.accused = tied ? null : leaders.getFirst();
        g.previousResult = new LiarGameRuntime.VoteResult(g.voteRound, tied, counts, g.accused);
        g.votes.clear();
        events.game(room, "VOTE_COMPLETED", Map.of("voteRound", g.voteRound));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("voteRound", g.voteRound);
        payload.put("tied", tied);
        payload.put("counts", counts.stream().map(c -> Map.of("playerId", c.playerId(), "voteCount", c.voteCount())).toList());
        if (!tied) payload.put("accusedPlayerId", g.accused);
        events.game(room, "VOTE_RESULT", payload);
        if (tied) {
            g.voteRound++;
            g.candidates = new LinkedHashSet<>(leaders);
            g.phase = REVOTING;
            events.game(room, "REVOTE_STARTED", Map.of("voteRound", g.voteRound, "candidatePlayerIds", List.copyOf(leaders)));
        } else {
            g.phase = LIAR_REVEAL;
            boolean caught = g.accused == g.liarId;
            events.game(room, "LIAR_REVEALED", Map.of("accusedPlayerId", g.accused, "accusedWasLiar", caught));
            if (caught) {
                g.phase = LIAR_GUESS;
                events.game(room, "LIAR_GUESS_STARTED", Map.of());
            } else finish(room, "LIAR");
        }
    }
    public boolean guess(RoomRuntime room, long playerId, String answer) {
        room.session.requireParticipant(playerId);
        var g = game(room);
        NOT_LIAR.require(g.liarId == playerId);
        GUESS_ALREADY_SUBMITTED.require(g.guess == null);
        INVALID_GAME_PHASE.require(g.phase == LIAR_GUESS && Objects.equals(g.accused, g.liarId));
        INVALID_ANSWER.require(answer != null && !answer.isBlank());
        String normalized = normalize(answer);
        boolean correct = normalize(g.keyword.keyword()).equals(normalized)
            || g.keyword.acceptedAnswers().stream().map(LiarGameService::normalize).anyMatch(normalized::equals);
        g.guess = answer.strip();
        g.correct = correct;
        events.game(room, "LIAR_GUESS_SUBMITTED", Map.of());
        finish(room, correct ? "LIAR" : "CITIZEN");
        return correct;
    }
    static String normalize(String value) { return value.strip().replaceAll("(?U)\\s+", " ").toLowerCase(Locale.ROOT); }
    private void finish(RoomRuntime room, String winner) {
        var g = game(room);
        g.winner = winner;
        g.phase = FINISHED;
        room.session.status = GameSessionRuntime.Status.FINISHED;
        events.game(room, "GAME_FINISHED", Map.of());
    }
    public void cancel(RoomRuntime room, String reason) {
        GAME_SESSION_ALREADY_FINISHED.require(!room.session.ended());
        var g = game(room);
        g.phase = CANCELLED;
        g.votes.clear();
        room.session.status = GameSessionRuntime.Status.CANCELLED;
        events.game(room, "GAME_CANCELLED", Map.of("reason", reason));
    }
    public void cancelIfLiarExpired(RoomRuntime room, long playerId) {
        if (!room.session.ended() && room.session.active(playerId) && game(room).liarId == playerId) cancel(room, "LIAR_LEFT");
    }
    public boolean canExclude(RoomRuntime room) { return game(room).voting(); }

    public void playerLeft(RoomRuntime room, long playerId) {
        if (room.session == null || room.session.ended() || !room.session.active(playerId)) return;
        removeParticipant(room, playerId);
    }

    public void removeParticipant(RoomRuntime room, long playerId) {
        room.session.requireParticipant(playerId);
        var g = game(room);
        if (g.liarId == playerId) {
            room.session.exclude(playerId);
            cancel(room, "LIAR_LEFT");
            return;
        }
        room.session.exclude(playerId);
        g.checked.remove(playerId);
        g.votes.remove(playerId);
        // User-confirmed: retain received votes and round candidacy; only the outgoing vote is invalidated.
        beginDiscussionIfReady(room);
        tallyIfComplete(room);
    }
}
