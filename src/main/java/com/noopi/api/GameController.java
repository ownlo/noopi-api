package com.noopi.api;

import com.noopi.application.GameApplication;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/rooms/{roomId}/game-sessions")
public class GameController {
    private final GameApplication app;
    public GameController(GameApplication app) { this.app = app; }
    public record CreateRequest(String gameType, Map<String,Object> config) {}
    public record VoteRequest(Long voteRound, Long targetPlayerId) {}
    public record GuessRequest(String answer) {}
    public record NightActionRequest(String actionType, Long targetPlayerId) {}
    public record JudgmentRequest(String choice) {}

    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public Responses.Session create(@PathVariable long roomId, @RequestHeader("X-Client-Id") String client, @RequestBody CreateRequest body) {
        return app.createSessionConfigured(roomId, client, body.gameType(), body.config());
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
    @PostMapping("/{gameSessionId}/mafia/role-check") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void mafiaRoleCheck(@PathVariable long roomId,@PathVariable long gameSessionId,@RequestHeader("X-Client-Id") String client){app.mafiaRoleCheck(roomId,gameSessionId,client);}
    @PostMapping("/{gameSessionId}/mafia/night-actions")
    public Map<String,Object> mafiaNightAction(@PathVariable long roomId,@PathVariable long gameSessionId,@RequestHeader("X-Client-Id") String client,@RequestBody NightActionRequest body){return app.mafiaNightAction(roomId,gameSessionId,client,body.actionType(),body.targetPlayerId());}
    @PostMapping("/{gameSessionId}/mafia/votes/start")
    public Responses.VoteRound startMafiaVote(@PathVariable long roomId,@PathVariable long gameSessionId,@RequestHeader("X-Client-Id") String client){return app.startMafiaVote(roomId,gameSessionId,client);}
    @PostMapping("/{gameSessionId}/mafia/votes") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void mafiaVote(@PathVariable long roomId,@PathVariable long gameSessionId,@RequestHeader("X-Client-Id") String client,@RequestBody VoteRequest body){app.mafiaVote(roomId,gameSessionId,client,body.voteRound(),body.targetPlayerId());}
    @PostMapping("/{gameSessionId}/mafia/judgment-votes") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void mafiaJudgment(@PathVariable long roomId,@PathVariable long gameSessionId,@RequestHeader("X-Client-Id") String client,@RequestBody JudgmentRequest body){app.mafiaJudgment(roomId,gameSessionId,client,body.choice());}
    @PostMapping("/{gameSessionId}/mafia/advance") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void advanceMafia(@PathVariable long roomId,@PathVariable long gameSessionId,@RequestHeader("X-Client-Id") String client){app.advanceMafia(roomId,gameSessionId,client);}
}
