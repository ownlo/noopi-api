package com.noopi.game.pig;

import com.noopi.game.session.GameSessionRuntime;
import com.noopi.room.RoomRuntime;
import java.util.*;
import org.springframework.stereotype.Component;
import static com.noopi.game.pig.PigGameRuntime.*;

@Component
public class PigStateProjection {
    public Map<String, Object> project(RoomRuntime room, long requester) {
        var game = (PigGameRuntime) room.session.game;
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("type", "PIG");
        state.put("phase", game.phase.name());
        state.put("targetScore", TARGET_SCORE);
        state.put("currentPlayerId", game.phase == Phase.PLAYING ? game.currentPlayer() : null);
        state.put("turnScore", game.turnScore);
        state.put("availableDiceValues", List.copyOf(game.availableDiceValues));
        state.put("removedDiceValues", List.copyOf(game.removedDiceValues));
        state.put("lastDiceValue", game.lastDiceValue);
        state.put("lastTurnOutcome", game.lastTurnOutcome == null ? null : game.lastTurnOutcome.name());
        state.put("lostTurnScore", game.lostTurnScore);
        state.put("bustProbability", game.availableDiceValues.isEmpty() ? 0.0 : 1.0 / game.availableDiceValues.size());
        state.put("players", game.players.values().stream().map(player -> {
            Map<String, Object> value = new LinkedHashMap<>();
            var participant = room.session.participants().get(player.playerId);
            value.put("playerId", player.playerId);
            value.put("nickname", participant.nickname());
            value.put("totalScore", player.totalScore);
            value.put("status", player.status.name());
            value.put("rank", player.rank);
            return value;
        }).toList());
        state.put("allowedActions", allowedActions(room.session, game, requester));
        if (game.phase == Phase.FINISHED) state.put("rankings", game.finishOrder.stream().map(id -> {
            var player = game.player(id);
            return Map.of("rank", player.rank, "playerId", id,
                "nickname", room.session.participants().get(id).nickname(), "totalScore", player.totalScore);
        }).toList());
        if (game.phase == Phase.CANCELLED) state.put("reason", game.cancelReason);
        return state;
    }
    private List<String> allowedActions(GameSessionRuntime session, PigGameRuntime game, long requester) {
        if (game.phase != Phase.PLAYING || !session.active(requester)
            || game.currentPlayer() != requester || game.player(requester).status != PlayerStatus.PLAYING) return List.of();
        return game.turnScore == 0 ? List.of("ROLL") : List.of("ROLL", "STOP");
    }
}
