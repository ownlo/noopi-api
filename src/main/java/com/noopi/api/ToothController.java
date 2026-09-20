package com.noopi.api;

import com.noopi.application.GameApplication;
import com.noopi.game.tooth.ToothGameRuntime;
import org.springframework.web.bind.annotation.*;
import static com.noopi.api.ErrorCode.BAD_REQUEST;

@RestController
@RequestMapping("/api/rooms/{roomId}/game-sessions/{gameSessionId}/tooth")
public class ToothController {
    private final GameApplication app;
    public ToothController(GameApplication app) { this.app = app; }
    public record SelectionRequest(Integer toothId) {}

    @PostMapping("/selections")
    public ToothGameRuntime.Selection select(@PathVariable long roomId, @PathVariable long gameSessionId,
                                             @RequestHeader("X-Client-Id") String client,
                                             @RequestHeader("Idempotency-Key") String actionKey,
                                             @RequestBody SelectionRequest body) {
        BAD_REQUEST.require(body != null && body.toothId() != null);
        return app.selectTooth(roomId, gameSessionId, client, body.toothId(), actionKey);
    }
}
