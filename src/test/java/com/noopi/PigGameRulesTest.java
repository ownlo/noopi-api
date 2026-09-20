package com.noopi;

import com.noopi.api.DomainException;
import com.noopi.api.ErrorCode;
import com.noopi.game.pig.*;
import com.noopi.game.session.GameSessionRuntime;
import com.noopi.realtime.RoomEvents;
import com.noopi.room.*;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static com.noopi.api.ErrorCode.*;
import static com.noopi.game.pig.PigGameRuntime.*;

class PigGameRulesTest {
    static class Dice extends Random {
        final Queue<Integer> indexes = new ArrayDeque<>();
        void indexes(int... values) { Arrays.stream(values).forEach(indexes::add); }
        @Override public int nextInt(int bound) {
            int value = indexes.remove();
            assertThat(value).isLessThan(bound);
            return value;
        }
    }
    static class Events implements RoomEvents {
        final List<String> types = new ArrayList<>();
        @Override public void publish(RoomRuntime room, String type, Long session, Map<String,Object> payload) {
            types.add(type);
        }
        @Override public void closePlayer(long room, long player) {}
        @Override public void closeRoom(long room) {}
    }
    Dice dice;
    Events events;
    RoomStore store;
    RoomRuntime room;
    PigGameService service;
    PigStateProjection projection;

    @BeforeEach void setup() {
        dice = new Dice(); events = new Events();
        store = new RoomStore(new Random(1), Clock.systemUTC());
        room = store.create(new PlayerRuntime(1, "host", "방장", "MALE"));
        room.players.put(2L, new PlayerRuntime(2, "guest", "손님", "FEMALE"));
        service = new PigGameService(dice, events);
        projection = new PigStateProjection();
        room.session = new GameSessionRuntime(100, "PIG", service.prepare());
    }
    PigGameRuntime game() { return (PigGameRuntime) room.session.game; }
    void start() { store.inRoom(room.id, value -> { service.start(value); return null; }); }
    void roll(long player, String key, int... indexes) {
        store.inRoom(room.id, value -> { dice.indexes(indexes); service.roll(value, player, key); return null; });
    }
    void stop(long player, String key) {
        store.inRoom(room.id, value -> { service.stop(value, player, key); return null; });
    }
    void error(ErrorCode code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class,
            exception -> assertThat(exception.code()).isEqualTo(code));
    }

    @Test void validatesTwoToSixPlayers() {
        room.players.remove(2L);
        error(NOT_ENOUGH_PLAYERS, this::start);
        for (long id = 2; id <= 7; id++)
            room.players.put(id, new PlayerRuntime(id, "c" + id, "참가" + id, "MALE"));
        error(TOO_MANY_PLAYERS, this::start);
    }

    @Test void successfulRollCanRepeatNumberAndRaisesBustProbability() {
        start(); long actor = game().currentPlayer();
        roll(actor, "roll-1", 99, 3); // success -> 5
        roll(actor, "roll-2", 99, 3); // same result can repeat
        assertThat(game().turnScore).isEqualTo(10);
        assertThat(game().successfulRollCount).isEqualTo(2);
        assertThat(projection.project(room, actor)).containsEntry("successfulRollCount", 2);
        assertThat(projection.project(room, actor)).containsEntry("bustProbability", 0.3);
        assertThat(projection.project(room, actor)).doesNotContainKeys("availableDiceValues", "removedDiceValues");
        assertThat(projection.project(room, actor).get("allowedActions")).isEqualTo(List.of("ROLL", "STOP"));
        assertThat(projection.project(room, 2L).get("allowedActions")).isEqualTo(List.of());
    }

    @Test void bustProbabilityRisesToNinetyPercentAndStaysThere() {
        start(); long actor = game().currentPlayer();
        for (int count = 1; count <= 8; count++) {
            roll(actor, "roll-" + count, 99, 4); // success -> 6
            assertThat(projection.project(room, actor)).containsEntry(
                "bustProbability", Math.min(10 + count * 10, 90) / 100.0);
        }
        assertThat(game().turnScore).isEqualTo(48);
        assertThat(game().successfulRollCount).isEqualTo(8);
        roll(actor, "bust", 0);
        assertThat(game().lostTurnScore).isEqualTo(48);
        assertThat(game().successfulRollCount).isZero();
    }

    @Test void initialBustProbabilityIsTenPercent() {
        start(); long actor = game().currentPlayer();
        assertThat(game().turnScore).isZero();
        assertThat(projection.project(room, actor)).containsEntry("bustProbability", 0.1);
    }

    @Test void rollingOneLosesOnlyTurnScoreAndAdvances() {
        start(); long actor = game().currentPlayer();
        roll(actor, "roll-1", 99, 2); // success -> 4
        roll(actor, "roll-2", 0); // bust -> 1
        assertThat(game().player(actor).totalScore).isZero();
        assertThat(game().turnScore).isZero();
        assertThat(game().lostTurnScore).isEqualTo(4);
        assertThat(game().lastTurnOutcome).isEqualTo(TurnOutcome.BUSTED);
        assertThat(game().currentPlayer()).isNotEqualTo(actor);
        assertThat(game().successfulRollCount).isZero();
        assertThat(projection.project(room, game().currentPlayer())).containsEntry("bustProbability", 0.1);
        assertThat(events.types).endsWith("PIG_ROLL_RESOLVED", "PIG_TURN_CHANGED");
    }

    @Test void stopBanksScoreAndDuplicateOrWrongPlayerActionsFail() {
        start(); long actor = game().currentPlayer(); long other = actor == 1 ? 2 : 1;
        error(NOT_CURRENT_PLAYER, () -> service.roll(room, other, "wrong"));
        roll(actor, "roll-1", 99, 1); // success -> 3
        error(DUPLICATE_ACTION, () -> service.stop(room, actor, "roll-1"));
        stop(actor, "stop-1");
        assertThat(game().player(actor).totalScore).isEqualTo(3);
        assertThat(game().currentPlayer()).isEqualTo(other);
        error(ACTION_NOT_ALLOWED, () -> service.stop(room, other, "stop-too-early"));
    }

    @Test void finishOrderNotScoreOrderAndLastPlayerIsAutomatic() {
        room.players.put(3L, new PlayerRuntime(3, "third", "세째", "MALE"));
        start();
        long first = game().currentPlayer();
        game().player(first).totalScore = 49;
        roll(first, "first-roll", 99, 0); // success -> 2 -> 51
        stop(first, "first-stop");
        assertThat(game().player(first).rank).isEqualTo(1);
        assertThat(game().currentPlayer()).isNotEqualTo(first);

        long second = game().currentPlayer();
        game().player(second).totalScore = 55;
        roll(second, "second-roll", 99, 0); // success -> 2 -> 57
        stop(second, "second-stop");

        long last = game().finishOrder.get(2);
        assertThat(room.session.status).isEqualTo(GameSessionRuntime.Status.FINISHED);
        assertThat(game().finishOrder).containsExactly(first, second, last);
        assertThat(game().player(first).totalScore).isEqualTo(51);
        assertThat(game().player(second).totalScore).isEqualTo(57);
        assertThat(game().player(last).totalScore).isZero();
        assertThat(projection.project(room, first).get("rankings")).asList().hasSize(3);
        assertThat(events.types.stream().filter("GAME_FINISHED"::equals).count()).isEqualTo(1);
    }
}
