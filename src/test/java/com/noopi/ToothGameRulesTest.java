package com.noopi;

import com.noopi.api.DomainException;
import com.noopi.api.ErrorCode;
import com.noopi.game.session.GameSessionRuntime;
import com.noopi.game.tooth.*;
import com.noopi.realtime.RoomEvents;
import com.noopi.room.*;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.*;
import static com.noopi.api.ErrorCode.*;
import static com.noopi.game.tooth.ToothGameRuntime.*;
import static org.assertj.core.api.Assertions.*;

class ToothGameRulesTest {
    static class FixedRandom extends Random {
        final Queue<Integer> values = new ArrayDeque<>();
        void values(int... next) { Arrays.stream(next).forEach(values::add); }
        @Override public int nextInt(int bound) {
            int value = values.isEmpty() ? 0 : values.remove();
            assertThat(value).isBetween(0, bound - 1);
            return value;
        }
    }
    static class Events implements RoomEvents {
        record Published(String type, Map<String, Object> payload) {}
        final List<Published> published = new ArrayList<>();
        @Override public void publish(RoomRuntime room, String type, Long session, Map<String, Object> payload) {
            published.add(new Published(type, new LinkedHashMap<>(payload)));
        }
        @Override public void closePlayer(long room, long player) {}
        @Override public void closeRoom(long room) {}
    }

    FixedRandom random;
    Events events;
    RoomStore store;
    RoomRuntime room;
    ToothGameService service;
    ToothStateProjection projection;

    @BeforeEach void setup() {
        random = new FixedRandom();
        events = new Events();
        store = new RoomStore(new Random(1), Clock.systemUTC());
        room = store.create(new PlayerRuntime(1, "host", "방장", "MALE"));
        room.players.put(2L, new PlayerRuntime(2, "guest", "손님", "FEMALE"));
        service = new ToothGameService(random, events);
        projection = new ToothStateProjection();
        room.session = new GameSessionRuntime(100, "TOOTH", service.prepare());
    }

    ToothGameRuntime game() { return (ToothGameRuntime) room.session.game; }
    void startWithBomb(int toothId) {
        random.values(0, toothId - 1); // two Players: one shuffle draw, then bomb draw
        store.inRoom(room.id, value -> { service.start(value); return null; });
    }
    void error(ErrorCode code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class,
            exception -> assertThat(exception.code()).isEqualTo(code));
    }

    @Test void validatesTwoToEightPlayers() {
        room.players.remove(2L);
        error(NOT_ENOUGH_PLAYERS, () -> service.start(room));
        for (long id = 2; id <= 9; id++)
            room.players.put(id, new PlayerRuntime(id, "c" + id, "참가" + id, "MALE"));
        error(TOO_MANY_PLAYERS, () -> service.start(room));
    }

    @Test void startsWithTwentyFourTeethRandomOrderAndHiddenBomb() {
        startWithBomb(7);
        var state = projection.project(room, game().currentPlayer());
        assertThat(game().turnOrder).containsExactlyInAnyOrder(1L, 2L).doesNotHaveDuplicates();
        assertThat(game().bombToothId).isEqualTo(7);
        assertThat(state).containsEntry("phase", "PLAYING").containsEntry("remainingToothCount", 24);
        assertThat(state.get("teeth")).asList().hasSize(24);
        assertThat(state).doesNotContainKey("bombToothId");
        assertThat(state.toString()).doesNotContain("BOMB");
        assertThat(state.get("allowedActions")).isEqualTo(List.of("SELECT_TOOTH"));
        long waiting = game().turnOrder.stream().filter(id -> id != game().currentPlayer()).findFirst().orElseThrow();
        assertThat(projection.project(room, waiting).get("allowedActions")).isEqualTo(List.of());
    }

    @Test void safeSelectionAdvancesOnceAndIdempotentRetryReturnsOriginalResult() {
        startWithBomb(24);
        long actor = game().currentPlayer();
        long waiting = actor == 1 ? 2 : 1;
        error(NOT_CURRENT_PLAYER, () -> service.select(room, waiting, 1, "wrong-player"));
        error(INVALID_TOOTH_ID, () -> service.select(room, actor, 0, "bad-tooth"));

        var result = service.select(room, actor, 1, "selection-1");
        assertThat(result.outcome()).isEqualTo(Outcome.SAFE);
        assertThat(result.nextCurrentTurnPlayerId()).isEqualTo(waiting);
        assertThat(game().selectedToothIds).containsExactly(1);
        assertThat(game().sequence).isEqualTo(1);
        assertThat(service.select(room, actor, 1, "selection-1")).isEqualTo(result);
        assertThat(game().sequence).isEqualTo(1);
        error(DUPLICATE_ACTION, () -> service.select(room, actor, 2, "selection-1"));
        error(TOOTH_ALREADY_SELECTED, () -> service.select(room, waiting, 1, "selection-2"));
        assertThat(events.published.stream().map(Events.Published::type)).containsExactly("GAME_STARTED", "TOOTH_SELECTED");
    }

    @Test void bombFinishesImmediatelyAndOnlyThenProjectionRevealsIt() {
        startWithBomb(7);
        long actor = game().currentPlayer();
        var result = service.select(room, actor, 7, "bomb");

        assertThat(result.outcome()).isEqualTo(Outcome.BOMB);
        assertThat(result.nextCurrentTurnPlayerId()).isNull();
        assertThat(game().loserPlayerId).isEqualTo(actor);
        assertThat(room.session.status).isEqualTo(GameSessionRuntime.Status.FINISHED);
        assertThat(game().phase).isEqualTo(Phase.FINISHED);
        var state = projection.project(room, actor);
        assertThat(state).containsEntry("currentTurnPlayerId", null).containsEntry("allowedActions", List.of());
        assertThat(state).doesNotContainKeys("rankings", "winnerPlayer");
        assertThat(state.get("result")).asString().contains("bombToothId=7", "playerId=" + actor);
        assertThat(events.published.stream().map(Events.Published::type))
            .containsExactly("GAME_STARTED", "TOOTH_SELECTED", "GAME_FINISHED");
        assertThat(events.published.get(1).payload()).doesNotContainKey("bombToothId");
        assertThat(service.select(room, actor, 7, "bomb")).isEqualTo(result);
    }
}
