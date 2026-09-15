package com.noopi.api;

import com.noopi.game.yut.YutGameService;
import com.noopi.room.RoomRuntime;
import com.noopi.room.RoomStore;
import java.util.function.BiFunction;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static com.noopi.api.ErrorCode.*;

@RestController
@RequestMapping("/api/rooms/{roomId}/game-sessions/{gameSessionId}/yut")
public class YutController {
    private final RoomStore rooms;
    private final YutGameService yut;
    public YutController(RoomStore rooms, YutGameService yut) { this.rooms = rooms; this.yut = yut; }
    public record TeamRequest(String team) {}
    public record TokenRequest(String moveTokenId) {}
    public record PieceRequest(String pieceId) {}
    public record PathRequest(String pathId) {}
    private <T> T action(long roomId, long sessionId, String client, BiFunction<RoomRuntime, Long, T> action) {
        return rooms.inRoom(roomId, room -> {
            long player = room.player(client).id;
            GAME_SESSION_NOT_FOUND.require(room.session != null && room.session.id == sessionId);
            INVALID_GAME_PHASE.require("YUT".equals(room.session.gameType));
            return action.apply(room, player);
        });
    }
    @PutMapping("/team")
    public ResponseEntity<Void> team(@PathVariable long roomId, @PathVariable long gameSessionId,
            @RequestHeader("X-Client-Id") String client, @RequestBody TeamRequest body) {
        action(roomId, gameSessionId, client, (r, p) -> { yut.selectTeam(r, p, body.team()); return null; });
        return ResponseEntity.noContent().build();
    }
    @PostMapping("/throws")
    public YutGameService.ThrowResult throwYut(@PathVariable long roomId, @PathVariable long gameSessionId,
            @RequestHeader("X-Client-Id") String client) {
        return action(roomId, gameSessionId, client, yut::throwYut);
    }
    @PostMapping("/move-selections")
    public ResponseEntity<Void> token(@PathVariable long roomId, @PathVariable long gameSessionId,
            @RequestHeader("X-Client-Id") String client, @RequestBody TokenRequest body) {
        action(roomId, gameSessionId, client, (r, p) -> { yut.selectToken(r, p, body.moveTokenId()); return null; });
        return ResponseEntity.noContent().build();
    }
    @PostMapping("/piece-selections")
    public ResponseEntity<YutGameService.MoveResult> piece(@PathVariable long roomId, @PathVariable long gameSessionId,
            @RequestHeader("X-Client-Id") String client, @RequestBody PieceRequest body) {
        var result = action(roomId, gameSessionId, client, (r, p) -> yut.selectPiece(r, p, body.pieceId()));
        return result == null ? ResponseEntity.accepted().build() : ResponseEntity.ok(result);
    }
    @PostMapping("/path-selections")
    public YutGameService.MoveResult path(@PathVariable long roomId, @PathVariable long gameSessionId,
            @RequestHeader("X-Client-Id") String client, @RequestBody PathRequest body) {
        return action(roomId, gameSessionId, client, (r, p) -> yut.selectPath(r, p, body.pathId()));
    }
}
