package com.noopi.api;

import com.noopi.application.GameApplication;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/rooms")
public class RoomController {
    private final GameApplication app;
    public RoomController(GameApplication app) { this.app = app; }
    public record PlayerRequest(String nickname, String gender) {}
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public Responses.CreatedRoom create(@RequestHeader("X-Client-Id") String client, @RequestBody PlayerRequest body) {
        return app.create(client, body.nickname(), body.gender());
    }
    @GetMapping("/by-code/{roomCode}")
    public Responses.Lookup lookup(@RequestHeader("X-Client-Id") String client, @PathVariable String roomCode) {
        return app.lookup(client, roomCode);
    }
    @PostMapping("/{roomId}/players") @ResponseStatus(HttpStatus.CREATED)
    public Responses.Player join(@PathVariable long roomId, @RequestHeader("X-Client-Id") String client, @RequestBody PlayerRequest body) {
        return app.join(roomId, client, body.nickname(), body.gender());
    }
    @DeleteMapping("/{roomId}/players/me") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(@PathVariable long roomId, @RequestHeader("X-Client-Id") String client) { app.leave(roomId, client); }
    @GetMapping("/{roomId}/state")
    public Responses.State state(@PathVariable long roomId, @RequestHeader("X-Client-Id") String client) { return app.state(roomId, client); }
}
