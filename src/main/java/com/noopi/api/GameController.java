package com.noopi.api;

import com.noopi.application.GameApplication;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/rooms/{roomId}/game-sessions")
public class GameController {
    private final GameApplication app;
    public GameController(GameApplication app) { this.app = app; }
    public record Config(String categoryCode) {}
    public record CreateRequest(String gameType, Config config) {}
    public record VoteRequest(Long voteRound, Long targetPlayerId) {}
    public record GuessRequest(String answer) {}

    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public Responses.Session create(@PathVariable long roomId, @RequestHeader("X-Client-Id") String client, @RequestBody CreateRequest body) {
        return app.createSession(roomId, client, body.gameType(), body.config() == null ? null : body.config().categoryCode());
    }
    @PostMapping("/{gameSessionId}/start") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void start(@PathVariable long roomId, @PathVariable long gameSessionId, @RequestHeader("X-Client-Id") String client) {
        app.start(roomId, gameSessionId, client);
    }
    @PostMapping("/{gameSessionId}/cancel") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable long roomId, @PathVariable long gameSessionId, @RequestHeader("X-Client-Id") String client) {
        app.cancel(roomId, gameSessionId, client);
    }
    @PostMapping("/{gameSessionId}/liar/role-check") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void check(@PathVariable long roomId, @PathVariable long gameSessionId, @RequestHeader("X-Client-Id") String client) {
        app.roleCheck(roomId, gameSessionId, client);
    }
    @PostMapping("/{gameSessionId}/liar/votes/start")
    public Responses.VoteRound startVote(@PathVariable long roomId, @PathVariable long gameSessionId, @RequestHeader("X-Client-Id") String client) {
        return app.startVote(roomId, gameSessionId, client);
    }
    @PostMapping("/{gameSessionId}/liar/votes") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void vote(@PathVariable long roomId, @PathVariable long gameSessionId, @RequestHeader("X-Client-Id") String client, @RequestBody VoteRequest body) {
        app.vote(roomId, gameSessionId, client, body.voteRound(), body.targetPlayerId());
    }
    @PostMapping("/{gameSessionId}/players/{playerId}/exclude") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void exclude(@PathVariable long roomId, @PathVariable long gameSessionId, @PathVariable long playerId, @RequestHeader("X-Client-Id") String client) {
        app.exclude(roomId, gameSessionId, client, playerId);
    }
    @PostMapping("/{gameSessionId}/liar/guess")
    public Responses.Guess guess(@PathVariable long roomId, @PathVariable long gameSessionId, @RequestHeader("X-Client-Id") String client, @RequestBody GuessRequest body) {
        return app.guess(roomId, gameSessionId, client, body.answer());
    }
    @PostMapping("/{gameSessionId}/blind/guesses")
    public Responses.Guess blindGuess(@PathVariable long roomId, @PathVariable long gameSessionId,
                                      @RequestHeader("X-Client-Id") String client, @RequestBody GuessRequest body) {
        return app.blindGuess(roomId, gameSessionId, client, body.answer());
    }
}
