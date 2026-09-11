package com.noopi.game.blind;

import com.noopi.content.BlindContent;
import com.noopi.game.session.GameSessionRuntime;
import com.noopi.game.session.GameSessionRuntime.Participant;
import com.noopi.realtime.RoomEvents;
import com.noopi.room.PlayerRuntime;
import com.noopi.room.RoomRuntime;
import java.util.ArrayDeque;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import static com.noopi.api.ErrorCode.*;

/** Called within RoomStore.inRoom, which is the atomic boundary for a room. */
@Service
public class BlindGameService {
    private final BlindContent content;
    private final RoomEvents events;
    private final int recentLimit;

    public BlindGameService(BlindContent content, RoomEvents events,
                            @Value("${noopi.recent-keyword-count:10}") int recentLimit) {
        this.content = content;
        this.events = events;
        this.recentLimit = Math.max(0, recentLimit);
    }

    public BlindGameRuntime prepare() { return new BlindGameRuntime(); }

    private BlindGameRuntime game(RoomRuntime room) { return (BlindGameRuntime) room.session.game; }

    public void requirePlayerCount(RoomRuntime room) {
        long count = room.players.values().stream()
            .filter(p -> p.connectionStatus == PlayerRuntime.ConnectionStatus.CONNECTED).count();
        NOT_ENOUGH_PLAYERS.require(count >= 2);
        TOO_MANY_PLAYERS.require(count <= 2);
    }

    public void start(RoomRuntime room) {
        GAME_SESSION_NOT_READY.require(room.session.status == GameSessionRuntime.Status.READY);
        requirePlayerCount(room);
        var players = room.players.values().stream()
            .filter(p -> p.connectionStatus == PlayerRuntime.ConnectionStatus.CONNECTED)
            .map(p -> new Participant(p.id, p.nickname)).toList();
        var recent = room.recentContent.getOrDefault("BLIND", new ArrayDeque<>());
        var selected = content.chooseDistinct(2, recent);
        room.session.start(players);
        var game = game(room);
        game.assignments.put(players.get(0).playerId(), selected.get(0));
        game.assignments.put(players.get(1).playerId(), selected.get(1));
        game.phase = BlindGameRuntime.Phase.GUESSING;
        selected.forEach(keyword -> recent.addLast(keyword.id()));
        while (recent.size() > recentLimit) recent.removeFirst();
        room.recentContent.put("BLIND", recent);
        events.game(room, "GAME_STARTED", Map.of("gameType", "BLIND"));
    }

    public boolean guess(RoomRuntime room, long playerId, String answer) {
        room.session.requireParticipant(playerId);
        GAME_SESSION_ALREADY_FINISHED.require(!room.session.ended());
        var game = game(room);
        INVALID_GAME_PHASE.require(game.phase == BlindGameRuntime.Phase.GUESSING);
        INVALID_ANSWER.require(answer != null && !answer.isBlank());
        game.attemptCounts.merge(playerId, 1, Integer::sum);
        boolean correct = normalize(game.assignments.get(playerId).keyword()).equals(normalize(answer));
        if (correct) {
            game.winnerPlayerId = playerId;
            game.phase = BlindGameRuntime.Phase.FINISHED;
            room.session.status = GameSessionRuntime.Status.FINISHED;
            events.game(room, "GAME_FINISHED", Map.of());
        }
        return correct;
    }

    public void cancel(RoomRuntime room, String reason) {
        GAME_SESSION_ALREADY_FINISHED.require(!room.session.ended());
        game(room).phase = BlindGameRuntime.Phase.CANCELLED;
        room.session.status = GameSessionRuntime.Status.CANCELLED;
        events.game(room, "GAME_CANCELLED", Map.of("reason", reason));
    }

    static String normalize(String value) { return value.replaceAll("(?U)\\s+", ""); }
}
