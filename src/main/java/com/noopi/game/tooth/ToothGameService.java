package com.noopi.game.tooth;

import com.noopi.game.session.GameSessionRuntime;
import com.noopi.realtime.RoomEvents;
import com.noopi.room.PlayerRuntime;
import com.noopi.room.RoomRuntime;
import java.util.*;
import java.util.random.RandomGenerator;
import org.springframework.stereotype.Service;
import static com.noopi.api.ErrorCode.*;
import static com.noopi.game.tooth.ToothGameRuntime.*;

/** Every method is called inside RoomStore.inRoom's atomic boundary. */
@Service
public class ToothGameService {
    private final RandomGenerator random;
    private final RoomEvents events;

    public ToothGameService(RandomGenerator random, RoomEvents events) {
        this.random = random;
        this.events = events;
    }

    public ToothGameRuntime prepare() { return new ToothGameRuntime(); }

    public void start(RoomRuntime room) {
        var participants = room.players.values().stream()
            .filter(player -> player.connectionStatus == PlayerRuntime.ConnectionStatus.CONNECTED)
            .toList();
        NOT_ENOUGH_PLAYERS.require(participants.size() >= 2);
        TOO_MANY_PLAYERS.require(participants.size() <= 8);
        room.session.start(participants.stream()
            .map(player -> new GameSessionRuntime.Participant(player.id, player.nickname)).toList());

        var game = game(room);
        game.turnOrder.addAll(participants.stream().map(player -> player.id).toList());
        shuffle(game.turnOrder);
        game.bombToothId = random.nextInt(TOOTH_COUNT) + 1;
        game.phase = Phase.PLAYING;
        events.game(room, "GAME_STARTED", Map.of("gameType", "TOOTH"));
    }

    public Selection select(RoomRuntime room, long playerId, int toothId, String actionKey) {
        BAD_REQUEST.require(actionKey != null && !actionKey.isBlank());
        var game = game(room);
        var existing = game.processedSelections.get(actionKey);
        if (existing != null) {
            DUPLICATE_ACTION.require(existing.toothId() == toothId && existing.playerId() == playerId);
            return existing;
        }

        GAME_SESSION_ALREADY_FINISHED.require(!room.session.ended());
        INVALID_GAME_PHASE.require(game.phase == Phase.PLAYING);
        room.session.requireParticipant(playerId);
        NOT_CURRENT_PLAYER.require(game.currentPlayer() == playerId);
        INVALID_TOOTH_ID.require(toothId >= 1 && toothId <= TOOTH_COUNT);
        TOOTH_ALREADY_SELECTED.require(!game.selectedToothIds.contains(toothId));

        game.selectedToothIds.add(toothId);
        var outcome = toothId == game.bombToothId ? Outcome.BOMB : Outcome.SAFE;
        Long nextPlayerId = null;
        if (outcome == Outcome.BOMB) {
            game.loserPlayerId = playerId;
            game.phase = Phase.FINISHED;
            room.session.status = GameSessionRuntime.Status.FINISHED;
        } else {
            game.turnIndex = (game.turnIndex + 1) % game.turnOrder.size();
            nextPlayerId = game.currentPlayer();
        }

        var selection = new Selection(++game.sequence, playerId, toothId, outcome, nextPlayerId);
        game.lastSelection = selection;
        game.processedSelections.put(actionKey, selection);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sequence", selection.sequence());
        payload.put("playerId", selection.playerId());
        payload.put("toothId", selection.toothId());
        payload.put("outcome", selection.outcome().name());
        payload.put("nextCurrentTurnPlayerId", selection.nextCurrentTurnPlayerId());
        events.game(room, "TOOTH_SELECTED", payload);
        if (outcome == Outcome.BOMB) events.game(room, "GAME_FINISHED", Map.of());
        return selection;
    }

    public void cancel(RoomRuntime room, String reason) {
        GAME_SESSION_ALREADY_FINISHED.require(!room.session.ended());
        var game = game(room);
        game.phase = Phase.CANCELLED;
        game.cancelReason = reason;
        room.session.status = GameSessionRuntime.Status.CANCELLED;
        events.game(room, "GAME_CANCELLED", Map.of("reason", reason));
    }

    private void shuffle(List<Long> values) {
        for (int index = values.size() - 1; index > 0; index--) {
            int swapIndex = random.nextInt(index + 1);
            Collections.swap(values, index, swapIndex);
        }
    }

    private ToothGameRuntime game(RoomRuntime room) {
        return (ToothGameRuntime) room.session.game;
    }
}
