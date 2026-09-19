package com.noopi.api;

import com.noopi.application.GameApplication;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/rooms/{roomId}/game-sessions/{gameSessionId}/pig")
public class PigController {
    private final GameApplication app;
    public PigController(GameApplication app) { this.app = app; }
    @PostMapping("/roll") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void roll(@PathVariable long roomId, @PathVariable long gameSessionId,
                     @RequestHeader("X-Client-Id") String client,
                     @RequestHeader("Idempotency-Key") String actionKey) {
        app.pigRoll(roomId, gameSessionId, client, actionKey);
    }
    @PostMapping("/stop") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void stop(@PathVariable long roomId, @PathVariable long gameSessionId,
                     @RequestHeader("X-Client-Id") String client,
                     @RequestHeader("Idempotency-Key") String actionKey) {
        app.pigStop(roomId, gameSessionId, client, actionKey);
    }
}
