package com.noopi.game.pig;

import com.noopi.game.session.GameSessionRuntime;
import com.noopi.realtime.RoomEvents;
import com.noopi.room.RoomRuntime;
import java.util.*;
import java.util.random.RandomGenerator;
import org.springframework.stereotype.Service;
import static com.noopi.api.ErrorCode.*;
import static com.noopi.game.pig.PigGameRuntime.*;

/** Every method is called inside RoomStore.inRoom's atomic boundary. */
@Service
public class PigGameService {
    private final RandomGenerator random;
    private final RoomEvents events;
    public PigGameService(RandomGenerator random, RoomEvents events) {
        this.random = random; this.events = events;
    }
    public PigGameRuntime prepare() { return new PigGameRuntime(); }
    public void start(RoomRuntime room) {
        var participants = room.players.values().stream()
            .filter(player -> player.connectionStatus == com.noopi.room.PlayerRuntime.ConnectionStatus.CONNECTED).toList();
        NOT_ENOUGH_PLAYERS.require(participants.size() >= 2);
        TOO_MANY_PLAYERS.require(participants.size() <= 6);
        room.session.start(participants.stream()
            .map(player -> new GameSessionRuntime.Participant(player.id, player.nickname)).toList());
        var game = game(room);
        participants.forEach(player -> {
            game.turnOrder.add(player.id);
            game.players.put(player.id, new PlayerState(player.id));
        });
        game.phase = Phase.PLAYING;
        resetTurn(game);
        events.game(room, "GAME_STARTED", Map.of("gameType", "PIG"));
    }
    public void roll(RoomRuntime room, long playerId, String actionKey) {
        var game = requireAction(room, playerId, actionKey);
        game.processedActionKeys.add(actionKey);
        int diceValue = random.nextInt(100) < game.bustProbabilityPercent()
            ? 1
            : 2 + random.nextInt(5);
        game.lastDiceValue = diceValue;
        game.lastTurnOutcome = null;
        game.lostTurnScore = 0;
        if (diceValue == 1) {
            game.lostTurnScore = game.turnScore;
            game.turnScore = 0;
            game.lastTurnOutcome = TurnOutcome.BUSTED;
            events.game(room, "PIG_ROLL_RESOLVED", Map.of(
                "playerId", playerId, "diceValue", diceValue, "busted", true));
            advanceTurn(room, playerId, TurnOutcome.BUSTED);
            return;
        }
        game.turnScore += diceValue;
        game.successfulRollCount++;
        events.game(room, "PIG_ROLL_RESOLVED", Map.of(
            "playerId", playerId, "diceValue", diceValue, "busted", false));
    }
    public void stop(RoomRuntime room, long playerId, String actionKey) {
        var game = requireAction(room, playerId, actionKey);
        ACTION_NOT_ALLOWED.require(game.turnScore > 0);
        game.processedActionKeys.add(actionKey);
        var player = game.player(playerId);
        player.totalScore += game.turnScore;
        game.turnScore = 0;
        game.lastTurnOutcome = TurnOutcome.STOPPED;
        game.lostTurnScore = 0;
        if (player.totalScore >= TARGET_SCORE) finishPlayer(room, player);
        if (finishIfOnlyOnePlaying(room)) return;
        advanceTurn(room, playerId, TurnOutcome.STOPPED);
    }
    public void cancel(RoomRuntime room, String reason) {
        GAME_SESSION_ALREADY_FINISHED.require(!room.session.ended());
        var game = game(room);
        game.phase = Phase.CANCELLED;
        game.cancelReason = reason;
        room.session.status = GameSessionRuntime.Status.CANCELLED;
        events.game(room, "GAME_CANCELLED", Map.of("reason", reason));
    }
    private PigGameRuntime requireAction(RoomRuntime room, long playerId, String actionKey) {
        var game = game(room);
        GAME_SESSION_ALREADY_FINISHED.require(!room.session.ended());
        INVALID_GAME_PHASE.require(game.phase == Phase.PLAYING);
        room.session.requireParticipant(playerId);
        BAD_REQUEST.require(actionKey != null && !actionKey.isBlank());
        DUPLICATE_ACTION.require(!game.processedActionKeys.contains(actionKey));
        NOT_CURRENT_PLAYER.require(game.currentPlayer() == playerId);
        return game;
    }
    private void finishPlayer(RoomRuntime room, PlayerState player) {
        if (player.status == PlayerStatus.FINISHED) return;
        var game = game(room);
        player.status = PlayerStatus.FINISHED;
        player.rank = game.finishOrder.size() + 1;
        game.finishOrder.add(player.playerId);
        events.game(room, "PIG_PLAYER_FINISHED", Map.of(
            "playerId", player.playerId, "rank", player.rank, "totalScore", player.totalScore));
    }
    private boolean finishIfOnlyOnePlaying(RoomRuntime room) {
        var game = game(room);
        var playing = game.players.values().stream()
            .filter(player -> player.status == PlayerStatus.PLAYING).toList();
        if (playing.size() != 1) return false;
        finishPlayer(room, playing.getFirst());
        game.phase = Phase.FINISHED;
        room.session.status = GameSessionRuntime.Status.FINISHED;
        game.successfulRollCount = 0;
        events.game(room, "GAME_FINISHED", Map.of());
        return true;
    }
    private void advanceTurn(RoomRuntime room, long previousPlayerId, TurnOutcome reason) {
        var game = game(room);
        do game.turnIndex = (game.turnIndex + 1) % game.turnOrder.size();
        while (game.player(game.currentPlayer()).status == PlayerStatus.FINISHED);
        long currentPlayerId = game.currentPlayer();
        resetTurnValues(game);
        game.lastTurnOutcome = reason;
        events.game(room, "PIG_TURN_CHANGED", Map.of(
            "previousPlayerId", previousPlayerId, "currentPlayerId", currentPlayerId, "reason", reason.name()));
    }
    private static void resetTurn(PigGameRuntime game) {
        resetTurnValues(game);
        game.lastDiceValue = null;
        game.lastTurnOutcome = null;
        game.lostTurnScore = 0;
    }
    private static void resetTurnValues(PigGameRuntime game) {
        game.turnScore = 0;
        game.successfulRollCount = 0;
    }
    private PigGameRuntime game(RoomRuntime room) { return (PigGameRuntime) room.session.game; }
}
