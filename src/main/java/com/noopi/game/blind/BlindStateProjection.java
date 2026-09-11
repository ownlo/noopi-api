package com.noopi.game.blind;

import com.noopi.game.session.GameSessionRuntime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Explicit allowlist: assignments are exposed only after finish. */
@Component
public class BlindStateProjection {
    public Map<String, Object> project(GameSessionRuntime session, long requester) {
        var game = (BlindGameRuntime) session.game;
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("type", "BLIND");
        state.put("phase", game.phase.name());
        if (game.phase == BlindGameRuntime.Phase.GUESSING && session.active(requester)) {
            long opponentId = session.activeIds().stream().filter(id -> id != requester).findFirst().orElseThrow();
            state.put("opponentPlayer", player(session, opponentId));
            state.put("opponentKeyword", game.assignments.get(opponentId).keyword());
        }
        if (game.phase == BlindGameRuntime.Phase.FINISHED) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("winnerPlayer", player(session, game.winnerPlayerId));
            result.put("keywordAssignments", session.participants().keySet().stream().map(id -> {
                Map<String, Object> assignment = new LinkedHashMap<>(player(session, id));
                assignment.put("keyword", game.assignments.get(id).keyword());
                return assignment;
            }).toList());
            state.put("result", result);
        }
        return state;
    }

    private Map<String, Object> player(GameSessionRuntime session, long id) {
        return Map.of("playerId", id, "nickname", session.participants().get(id).nickname());
    }
}
