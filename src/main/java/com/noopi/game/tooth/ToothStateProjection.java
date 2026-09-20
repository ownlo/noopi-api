package com.noopi.game.tooth;

import com.noopi.game.session.GameSessionRuntime;
import com.noopi.room.RoomRuntime;
import java.util.*;
import org.springframework.stereotype.Component;
import static com.noopi.game.tooth.ToothGameRuntime.*;

@Component
public class ToothStateProjection {
    public Map<String, Object> project(RoomRuntime room, long requester) {
        var game = (ToothGameRuntime) room.session.game;
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("type", "TOOTH");
        state.put("phase", game.phase.name());
        if (game.phase == Phase.READY) return state;
        if (game.phase == Phase.CANCELLED) {
            state.put("reason", game.cancelReason);
            return state;
        }

        state.put("turnOrderPlayerIds", List.copyOf(game.turnOrder));
        state.put("currentTurnPlayerId", game.phase == Phase.PLAYING ? game.currentPlayer() : null);
        state.put("remainingToothCount", game.remainingToothCount());
        state.put("teeth", teeth(game));
        state.put("lastSelection", selection(game.lastSelection));
        state.put("allowedActions", allowedActions(room.session, game, requester));
        if (game.phase == Phase.FINISHED) {
            var loser = room.session.participants().get(game.loserPlayerId);
            state.put("result", Map.of(
                "loserPlayer", Map.of("playerId", loser.playerId(), "nickname", loser.nickname()),
                "bombToothId", game.bombToothId));
        }
        return state;
    }

    private List<Map<String, Object>> teeth(ToothGameRuntime game) {
        List<Map<String, Object>> teeth = new ArrayList<>(TOOTH_COUNT);
        for (int toothId = 1; toothId <= TOOTH_COUNT; toothId++) {
            teeth.add(Map.of(
                "toothId", toothId,
                "row", toothId <= 12 ? "UPPER" : "LOWER",
                "status", game.selectedToothIds.contains(toothId) ? "SELECTED" : "AVAILABLE"));
        }
        return teeth;
    }

    private Map<String, Object> selection(Selection selection) {
        if (selection == null) return null;
        return Map.of(
            "sequence", selection.sequence(),
            "playerId", selection.playerId(),
            "toothId", selection.toothId(),
            "outcome", selection.outcome().name());
    }

    private List<String> allowedActions(GameSessionRuntime session, ToothGameRuntime game, long requester) {
        if (game.phase != Phase.PLAYING || !session.active(requester) || game.currentPlayer() != requester)
            return List.of();
        return List.of("SELECT_TOOTH");
    }
}
