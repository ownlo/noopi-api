package com.noopi.game.mafia;

import com.noopi.game.session.GameSessionRuntime;
import com.noopi.room.PlayerRuntime;
import com.noopi.room.RoomRuntime;
import java.util.*;
import org.springframework.stereotype.Component;
import static com.noopi.game.mafia.MafiaGameRuntime.Phase.*;

@Component
public class MafiaStateProjection {
    private final MafiaGameService service;
    public MafiaStateProjection(MafiaGameService service) { this.service = service; }
    public Map<String, Object> project(RoomRuntime room, long requester) {
        var session = room.session;
        var g = (MafiaGameRuntime) session.game; Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "MAFIA"); s.put("phase", g.phase.name());
        if (g.phase == READY) {
            int count = (int) room.players.values().stream()
                .filter(p -> p.connectionStatus == PlayerRuntime.ConnectionStatus.CONNECTED).count();
            s.put("participantCount", count); s.put("roleComposition", service.composition(count)); return s;
        }
        if (g.phase == CANCELLED) return s;
        if (g.phase == FINISHED) {
            s.put("result", Map.of("winnerTeam", g.winnerTeam, "players", session.participants().values().stream().map(p -> Map.of(
                "playerId", p.playerId(), "nickname", p.nickname(), "role", g.roles.get(p.playerId()).name(), "alive", g.alive.contains(p.playerId()))).toList()));
            return s;
        }
        boolean participant = session.active(requester); boolean alive = g.alive.contains(requester);
        s.put("myRole", participant ? g.roles.get(requester).name() : null); s.put("alive", alive);
        s.put("players", session.participants().values().stream().map(p -> {
            Map<String, Object> value = new LinkedHashMap<>(); value.put("playerId", p.playerId()); value.put("nickname", p.nickname());
            value.put("alive", g.alive.contains(p.playerId())); value.put("revealedRole", revealed(g, p.playerId())); return value;
        }).toList());
        if (participant && g.roles.get(requester) == MafiaGameRuntime.Role.MAFIA) s.put("mafiaTeammates", g.roles.entrySet().stream()
            .filter(e -> e.getValue() == MafiaGameRuntime.Role.MAFIA && e.getKey() != requester).map(e -> teammate(session, g, e.getKey())).toList());
        if (participant && g.roles.get(requester) == MafiaGameRuntime.Role.POLICE)
            s.put("investigationHistory", g.investigations.getOrDefault(requester, List.of()));
        if (g.phase == ROLE_REVEAL) {
            s.put("roleChecked", g.checked.contains(requester)); s.put("roleCheckedCount", g.checked.size()); s.put("participantCount", session.activeIds().size());
        } else if (g.phase == FIRST_NIGHT || g.phase == NIGHT) night(s, session, g, requester, alive);
        else if (g.phase == DAY) day(s, g, requester);
        else if (g.voting()) vote(s, session, g, requester, alive);
        else if (g.phase == VOTE_RESULT) { s.put("voteResult", g.previousVoteResult); s.put("canAdvance", requester == room.hostPlayerId); }
        else if (g.phase == JUDGMENT) judgment(s, session, g, requester, alive);
        else if (g.phase == JUDGMENT_RESULT) { s.put("accusedPlayer", player(session, g.accused)); s.put("executeCount", service.executeCount(g));
            s.put("saveCount", service.saveCount(g)); s.put("executed", g.executed); s.put("canAdvance", requester == room.hostPlayerId); }
        else if (g.phase == EXECUTION) { s.put("executionResult", death(g.executionResult)); s.put("canAdvance", requester == room.hostPlayerId); }
        else if (g.phase == NIGHT_RESULT) { s.put("nightResult", nightResult(g.lastNightResult, requester)); s.put("canAdvance", requester == room.hostPlayerId); }
        return s;
    }
    private void night(Map<String,Object> s, GameSessionRuntime session, MafiaGameRuntime g, long requester, boolean alive) {
        s.put("nightNo", g.nightNo); if (!alive) return;
        var submitted = g.nightActions.containsKey(requester); Map<String,Object> action = new LinkedHashMap<>();
        action.put("actionType", service.expected(g, requester).name()); action.put("submitted", submitted);
        if (!submitted) action.put("eligibleTargets", service.eligible(g, requester).stream().map(id -> player(session,id)).toList());
        s.put("nightAction", action); s.put("nightProgress", Map.of("completedActionCount", g.nightActions.size(), "requiredActionCount", g.alive.size()));
    }
    private void day(Map<String,Object> s, MafiaGameRuntime g, long requester) {
        long mafia = g.alive.stream().filter(id -> g.roles.get(id) == MafiaGameRuntime.Role.MAFIA).count();
        s.put("dayNo", g.dayNo); s.put("remainingTeamCounts", Map.of("mafia", mafia, "citizenTeam", g.alive.size()-mafia));
        s.put("lastNightResult", nightResult(g.lastNightResult, requester));
    }
    private void vote(Map<String,Object> s, GameSessionRuntime session, MafiaGameRuntime g, long requester, boolean alive) {
        Map<String,Object> vote = new LinkedHashMap<>(); vote.put("round", g.voteRound);
        vote.put("eligibleCandidates", alive ? g.candidates.stream().filter(id -> id != requester).map(id -> player(session,id)).toList() : List.of());
        vote.put("requiredVoteCount", g.alive.size()); vote.put("completedVoteCount", g.votes.size()); vote.put("myVoteSubmitted", alive && g.votes.containsKey(requester)); s.put("vote", vote);
        if (g.previousVoteResult != null) s.put("previousVoteResult", g.previousVoteResult);
    }
    private void judgment(Map<String,Object> s, GameSessionRuntime session, MafiaGameRuntime g, long requester, boolean alive) {
        s.put("accusedPlayer", player(session,g.accused)); Map<String,Object> j = new LinkedHashMap<>(service.judgmentProgress(g));
        j.put("canVote", alive && requester != g.accused); j.put("myVoteSubmitted", g.judgmentVotes.containsKey(requester)); s.put("judgment", j);
    }
    private Object revealed(MafiaGameRuntime g, long id) {
        return g.revealedRoles.containsKey(id) ? g.revealedRoles.get(id).name() : null;
    }
    private Map<String,Object> nightResult(MafiaGameRuntime.NightResult result, long requester) {
        Map<String,Object> value = new LinkedHashMap<>(); value.put("nightNo", result.nightNo());
        value.put("deadPlayer", result.deadPlayer() == null ? null : death(result.deadPlayer()));
        value.put("mySuspicionCount", result.suspicionCounts().getOrDefault(requester,0)); return value;
    }
    private Map<String,Object> death(MafiaGameRuntime.Death d) { return Map.of("playerId",d.playerId(),"nickname",d.nickname(),"revealedRole",d.revealedRole().name()); }
    private Map<String,Object> player(GameSessionRuntime s,long id) { var p=s.participants().get(id); return Map.of("playerId",id,"nickname",p.nickname()); }
    private Map<String,Object> teammate(GameSessionRuntime s,MafiaGameRuntime g,long id) { var value=new LinkedHashMap<String,Object>(player(s,id)); value.put("alive",g.alive.contains(id)); return value; }
}
