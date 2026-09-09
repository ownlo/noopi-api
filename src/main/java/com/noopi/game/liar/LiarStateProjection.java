package com.noopi.game.liar;

import com.noopi.game.session.GameSessionRuntime;
import java.util.*;
import org.springframework.stereotype.Component;
import static com.noopi.game.liar.LiarGameRuntime.Phase.*;

/** Explicit allowlist: no runtime, role map, answer aliases or individual votes leave this boundary. */
@Component
public class LiarStateProjection {
    public Map<String, Object> project(GameSessionRuntime session, long requester) {
        var g = (LiarGameRuntime) session.game;
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("type", "LIAR");
        state.put("phase", g.phase.name());
        if (g.phase == READY) {
            state.put("categoryCode", g.categoryCode);
            state.put("categoryName", g.categoryName);
            return state;
        }
        if (g.phase == CANCELLED) return state;
        boolean participant = session.active(requester);
        if (g.phase == ROLE_REVEAL || g.phase == DISCUSSION || g.phase == LIAR_GUESS) {
            state.put("myRole", participant ? (g.liarId == requester ? "LIAR" : "CITIZEN") : null);
            state.put("keyword", participant && g.liarId != requester ? g.keyword.keyword() : null);
        }
        if (g.phase == LIAR_GUESS) {
            // The final accusation has already revealed the liar to the whole room.
            state.put("liarPlayer", player(session, g.liarId));
        }
        if (g.phase == ROLE_REVEAL) {
            state.put("roleChecked", participant && g.checked.contains(requester));
            state.put("roleCheckedCount", g.checked.size());
            state.put("participantCount", session.activeIds().size());
            state.put("playerRoleCheckStatuses", session.activeIds().stream()
                .map(id -> Map.of("playerId", id, "checked", g.checked.contains(id))).toList());
        }
        if (g.phase == DISCUSSION) state.put("firstSpeakerPlayerId", g.firstSpeaker);
        if (g.voting()) {
            Map<String, Object> vote = new LinkedHashMap<>();
            vote.put("round", g.voteRound);
            vote.put("eligibleCandidates", participant ? g.candidates.stream().filter(id -> id != requester)
                .map(id -> player(session, id)).toList() : List.of());
            vote.put("requiredVoteCount", session.activeIds().size());
            vote.put("completedVoteCount", g.votes.size());
            vote.put("myVoteSubmitted", participant && g.votes.containsKey(requester));
            vote.put("playerVoteStatuses", session.activeIds().stream()
                .map(id -> Map.of("playerId", id, "submitted", g.votes.containsKey(id))).toList());
            state.put("vote", vote);
            if (g.previousResult != null) state.put("previousVoteResult", g.previousResult);
        } else if (g.previousResult != null) state.put("voteResult", g.previousResult);
        if (g.phase == FINISHED) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("winner", g.winner);
            result.put("liarPlayer", player(session, g.liarId));
            result.put("keyword", g.keyword.keyword());
            result.put("accusedPlayer", player(session, g.accused));
            result.put("liarGuess", g.guess == null ? null : Map.of("answer", g.guess, "correct", g.correct));
            state.put("result", result);
        }
        return state;
    }
    private Map<String, Object> player(GameSessionRuntime session, long id) {
        return Map.of("playerId", id, "nickname", session.participants().get(id).nickname());
    }
}
