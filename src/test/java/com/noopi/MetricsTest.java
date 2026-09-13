package com.noopi;

import com.noopi.application.RoomCleanup;
import com.noopi.game.session.GameSessionRuntime;
import com.noopi.metrics.*;
import com.noopi.room.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MetricsTest {
    @Test void midnightLifecycleCleanupAndDuplicateObservation() {
        var clock = new GameRulesTest.MutableClock();
        clock.now = Instant.parse("2026-09-12T14:59:00Z");
        var metrics = new MetricsCollector(clock);
        var rooms = new RoomStore(new Random(3), clock, metrics);
        var room = rooms.create(new PlayerRuntime(rooms.nextId(), "host", "방장", "MALE"));
        rooms.inRoom(room.id, r -> {
            r.session = new GameSessionRuntime(1, "BLIND", null);
            r.session.start(List.of(new GameSessionRuntime.Participant(1, "가"), new GameSessionRuntime.Participant(2, "나")));
            return null;
        });
        clock.now = clock.now.plusSeconds(120);
        rooms.inRoom(room.id, r -> { r.session.status = GameSessionRuntime.Status.FINISHED; return null; });
        rooms.inRoom(room.id, r -> null);
        new RoomCleanup(rooms, new GameRulesTest.RecordingEvents(), clock, Duration.ZERO).clean();
        rooms.remove(room);
        var snapshot = metrics.snapshot();
        assertThat(snapshot.rooms()).containsExactlyInAnyOrder(
            new MetricsCollector.RoomTotals(LocalDate.parse("2026-09-12"), 1, 0, 0, 0),
            new MetricsCollector.RoomTotals(LocalDate.parse("2026-09-13"), 0, 1, 120000, 120000));
        assertThat(snapshot.games()).containsExactlyInAnyOrder(
            new MetricsCollector.GameTotals(new MetricsCollector.GameKey(LocalDate.parse("2026-09-12"), "BLIND"), 1, 0, 0, 2),
            new MetricsCollector.GameTotals(new MetricsCollector.GameKey(LocalDate.parse("2026-09-13"), "BLIND"), 0, 1, 0, 0));
    }
    @Test void readyCancellationDoesNotCountAndRoomRemovalCancelsStartedGame() {
        var clock = Clock.systemUTC();
        var metrics = new MetricsCollector(clock);
        var rooms = new RoomStore(new Random(3), clock, metrics);
        var room = rooms.create(new PlayerRuntime(rooms.nextId(), "host", "방장", "MALE"));
        rooms.inRoom(room.id, r -> {
            r.session = new GameSessionRuntime(1, "LIAR", null);
            r.session.status = GameSessionRuntime.Status.CANCELLED;
            return null;
        });
        assertThat(metrics.snapshot().games()).isEmpty();
        rooms.inRoom(room.id, r -> {
            r.session = new GameSessionRuntime(2, "LIAR", null);
            r.session.start(List.of(new GameSessionRuntime.Participant(1, "가")));
            return null;
        });
        rooms.remove(room);
        assertThat(metrics.snapshot().games()).singleElement().satisfies(g -> {
            assertThat(g.started()).isEqualTo(1);
            assertThat(g.cancelled()).isEqualTo(1);
            assertThat(g.finished()).isZero();
        });
    }
    @Test void concurrentRoomActionsCountTerminalTransitionOnce() throws Exception {
        var clock = Clock.systemUTC();
        var metrics = new MetricsCollector(clock);
        var rooms = new RoomStore(new Random(3), clock, metrics);
        var room = rooms.create(new PlayerRuntime(rooms.nextId(), "host", "방장", "MALE"));
        rooms.inRoom(room.id, r -> {
            r.session = new GameSessionRuntime(1, "BLIND", null);
            r.session.start(List.of(new GameSessionRuntime.Participant(1, "가")));
            return null;
        });
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Callable<Void>> actions = new ArrayList<>();
            for (int i = 0; i < 100; i++) actions.add(() -> rooms.inRoom(room.id, r -> {
                r.session.status = GameSessionRuntime.Status.FINISHED; return null;
            }));
            for (var result : pool.invokeAll(actions)) result.get();
        }
        assertThat(metrics.snapshot().games()).singleElement().satisfies(g -> {
            assertThat(g.started()).isEqualTo(1); assertThat(g.finished()).isEqualTo(1);
        });
    }
}
