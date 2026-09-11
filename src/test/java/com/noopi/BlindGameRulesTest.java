package com.noopi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noopi.api.DomainException;
import com.noopi.api.ErrorCode;
import com.noopi.application.GameApplication;
import com.noopi.content.LiarContent;
import com.noopi.content.BlindContent;
import com.noopi.game.blind.BlindGameService;
import com.noopi.game.blind.BlindStateProjection;
import com.noopi.game.liar.LiarGameService;
import com.noopi.game.liar.LiarStateProjection;
import com.noopi.realtime.RoomEvents;
import com.noopi.room.RoomRuntime;
import com.noopi.room.RoomStore;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static com.noopi.api.ErrorCode.*;
import static org.assertj.core.api.Assertions.*;

class BlindGameRulesTest {
    static class RecordingEvents implements RoomEvents {
        record Sent(String type, Map<String, Object> payload) {}
        final List<Sent> sent = new CopyOnWriteArrayList<>();
        public void publish(RoomRuntime room, String type, Long sessionId, Map<String, Object> payload) {
            sent.add(new Sent(type, Map.copyOf(payload)));
        }
        public void closePlayer(long roomId, long playerId) {}
        public void closeRoom(long roomId) {}
        long count(String type) { return sent.stream().filter(event -> event.type().equals(type)).count(); }
    }

    final ObjectMapper json = new ObjectMapper();
    RecordingEvents events;
    RoomStore store;
    GameApplication app;
    long room;
    long session;
    long hostId;
    long guestId;

    @BeforeEach void setup() {
        events = new RecordingEvents();
        var random = new Random(17);
        store = new RoomStore(random, Clock.systemUTC());
        LiarContent content = new LiarContent() {
            public List<Category> categories() { return List.of(new Category("RANDOM", "랜덤", true)); }
            public void validateCategory(String code) { INVALID_CATEGORY.require("RANDOM".equals(code)); }
            public Keyword choose(String code, Collection<Long> recent) { return new Keyword(1, "바다"); }
        };
        BlindContent blindContent = (count, recent) -> List.of(
            new BlindContent.Keyword(1, "바다"), new BlindContent.Keyword(2, "기린"));
        var liar = new LiarGameService(content, random, events, 10);
        var blind = new BlindGameService(blindContent, events, 10);
        app = new GameApplication(store, liar, new LiarStateProjection(), blind, new BlindStateProjection(),
            events, Clock.systemUTC(), Duration.ofMinutes(2));
    }

    void twoPlayers() {
        var created = app.create("host", "방장", "MALE");
        room = created.room().roomId();
        hostId = created.me().playerId();
        guestId = app.join(room, "guest", "손님", "FEMALE").playerId();
    }

    void start() {
        twoPlayers();
        session = app.createSession(room, "host", "BLIND", null).gameSessionId();
        app.start(room, session, "host");
    }

    Map<String, Object> state(String client) { return app.state(room, client).gameSession().gameState(); }

    void error(ErrorCode code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class,
            error -> assertThat(error.code()).isEqualTo(code));
    }

    @Test void creationRequiresExactlyTwoPlayersAndHost() {
        var created = app.create("host", "방장", "MALE");
        room = created.room().roomId();
        error(NOT_ENOUGH_PLAYERS, () -> app.createSession(room, "host", "BLIND", null));
        app.join(room, "guest", "손님", "FEMALE");
        error(NOT_ROOM_HOST, () -> app.createSession(room, "guest", "BLIND", null));
        session = app.createSession(room, "host", "BLIND", null).gameSessionId();
        assertThat(app.state(room, "host").gameSession().status()).isEqualTo("READY");

        app.cancel(room, session, "host");
        app.join(room, "third", "셋째", "MALE");
        error(TOO_MANY_PLAYERS, () -> app.createSession(room, "host", "BLIND", null));
    }

    @Test void startRevalidatesCountAndHost() {
        twoPlayers();
        session = app.createSession(room, "host", "BLIND", null).gameSessionId();
        error(NOT_ROOM_HOST, () -> app.start(room, session, "guest"));
        app.join(room, "third", "셋째", "MALE");
        error(TOO_MANY_PLAYERS, () -> app.start(room, session, "host"));
    }

    @Test void guessingProjectionContainsOnlyOpponentKeywordAndRestoresOnReconnect() throws Exception {
        start();
        var host = state("host");
        var guest = state("guest");
        assertThat(host).containsEntry("phase", "GUESSING").containsEntry("opponentKeyword", "기린");
        assertThat(guest).containsEntry("phase", "GUESSING").containsEntry("opponentKeyword", "바다");
        assertThat(((Map<?, ?>) host.get("opponentPlayer")).get("playerId")).isEqualTo(guestId);
        assertThat(((Map<?, ?>) guest.get("opponentPlayer")).get("playerId")).isEqualTo(hostId);
        assertThat(json.writeValueAsString(host)).doesNotContain("바다", "myKeyword", "assignments");
        assertThat(json.writeValueAsString(guest)).doesNotContain("기린", "myKeyword", "assignments");
        assertThat(json.writeValueAsString(events.sent)).doesNotContain("바다", "기린");

        store.inRoom(room, runtime -> { runtime.player("guest").connectionStatus = com.noopi.room.PlayerRuntime.ConnectionStatus.DISCONNECTED; return null; });
        app.join(room, "guest", "무시", "MALE");
        assertThat(state("guest")).containsEntry("opponentKeyword", "바다");
    }

    @Test void wrongAnswersAllowUnlimitedRetryAndCorrectAnswerFinishes() {
        start();
        assertThat(app.blindGuess(room, session, "host", "오답").correct()).isFalse();
        assertThat(app.blindGuess(room, session, "host", "또 오답").correct()).isFalse();
        assertThat(state("host").get("phase")).isEqualTo("GUESSING");
        error(INVALID_ANSWER, () -> app.blindGuess(room, session, "host", " \t "));
        assertThat(app.blindGuess(room, session, "host", " 바 다 ").correct()).isTrue();
        assertThat(state("host").get("phase")).isEqualTo("FINISHED");
        error(GAME_SESSION_ALREADY_FINISHED, () -> app.blindGuess(room, session, "guest", "기린"));
    }

    @Test void finishedStateRevealsOneWinnerAndBothAssignments() throws Exception {
        start();
        app.blindGuess(room, session, "guest", "기린");
        for (String client : List.of("host", "guest")) {
            var result = (Map<?, ?>) state(client).get("result");
            assertThat(((Map<?, ?>) result.get("winnerPlayer")).get("playerId")).isEqualTo(guestId);
            assertThat((List<?>) result.get("keywordAssignments")).hasSize(2);
            assertThat(json.writeValueAsString(result)).contains("바다", "기린", "방장", "손님");
        }
        assertThat(events.count("GAME_FINISHED")).isEqualTo(1);
    }

    @Test void outsiderCannotSubmitAndWrongGameSessionIsRejected() {
        start();
        error(PLAYER_NOT_IN_ROOM, () -> app.blindGuess(room, session, "outsider", "바다"));
        error(GAME_SESSION_NOT_FOUND, () -> app.blindGuess(room, session + 999, "host", "바다"));
    }

    @Test void concurrentCorrectAnswersProduceExactlyOneWinnerAndOneFinishEvent() throws Exception {
        start();
        var successes = new AtomicInteger();
        var finished = new AtomicInteger();
        runTogether(List.of(
            () -> submit("host", "바다", successes, finished),
            () -> submit("guest", "기린", successes, finished)));
        assertThat(successes.get()).isEqualTo(1);
        assertThat(finished.get()).isEqualTo(1);
        assertThat(events.count("GAME_FINISHED")).isEqualTo(1);
        var result = (Map<?, ?>) state("host").get("result");
        long winner = ((Number) ((Map<?, ?>) result.get("winnerPlayer")).get("playerId")).longValue();
        assertThat(winner).isIn(hostId, guestId);
    }

    void submit(String client, String answer, AtomicInteger successes, AtomicInteger finished) {
        try {
            assertThat(app.blindGuess(room, session, client, answer).correct()).isTrue();
            successes.incrementAndGet();
        } catch (DomainException error) {
            assertThat(error.code()).isEqualTo(GAME_SESSION_ALREADY_FINISHED);
            finished.incrementAndGet();
        }
    }

    void runTogether(List<Runnable> actions) throws Exception {
        try (var pool = Executors.newFixedThreadPool(actions.size())) {
            var ready = new CountDownLatch(actions.size());
            var go = new CountDownLatch(1);
            var futures = actions.stream().map(action -> pool.submit(() -> {
                ready.countDown();
                if (!go.await(5, TimeUnit.SECONDS)) throw new AssertionError("barrier timeout");
                action.run();
                return null;
            })).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
        }
    }
}
