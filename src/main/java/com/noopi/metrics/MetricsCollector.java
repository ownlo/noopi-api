package com.noopi.metrics;

import com.noopi.game.session.GameSessionRuntime;
import com.noopi.room.RoomRuntime;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** Only aggregate values leave the room lock; no player or game secrets are retained. */
@Component
public class MetricsCollector {
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    public record RoomTotals(LocalDate date, long created, long closed, long duration, long maxDuration) {}
    public record GameKey(LocalDate date, String type) {}
    public record GameTotals(GameKey key, long started, long finished, long cancelled, long participants) {}
    public record Snapshot(List<RoomTotals> rooms, List<GameTotals> games) {}
    private final Clock clock;
    private final Map<LocalDate, RoomTotals> rooms = new HashMap<>();
    private final Map<GameKey, GameTotals> games = new HashMap<>();

    public MetricsCollector(Clock clock) { this.clock = clock; }
    private LocalDate today() { return clock.instant().atZone(ZONE).toLocalDate(); }
    public synchronized void created(RoomRuntime room) {
        var date = room.createdAt.atZone(ZONE).toLocalDate();
        var old = rooms.getOrDefault(date, new RoomTotals(date, 0, 0, 0, 0));
        rooms.put(date, new RoomTotals(date, old.created + 1, old.closed, old.duration, old.maxDuration));
    }
    public synchronized void closed(RoomRuntime room) {
        var date = today();
        var old = rooms.getOrDefault(date, new RoomTotals(date, 0, 0, 0, 0));
        long duration = Math.max(0, Duration.between(room.createdAt, clock.instant()).toMillis());
        rooms.put(date, new RoomTotals(date, old.created, old.closed + 1,
            old.duration + duration, Math.max(old.maxDuration, duration)));
        observe(room);
    }
    /** Caller holds this room's monitor. Flags prevent duplicate counts on reads/retries. */
    public synchronized void observe(RoomRuntime room) {
        var session = room.session;
        if (session == null) return;
        // A participant snapshot is created only when the game actually starts.
        if (!session.metricsStarted && !session.participants().isEmpty()) {
            session.metricsStarted = true;
            add(session, 1, 0, 0, session.participants().size());
        }
        if (session.metricsStarted && !session.metricsEnded && (session.ended() || room.closed)) {
            session.metricsEnded = true;
            boolean finished = session.status == GameSessionRuntime.Status.FINISHED;
            add(session, 0, finished ? 1 : 0, finished ? 0 : 1, 0);
        }
    }
    private void add(GameSessionRuntime session, long started, long finished, long cancelled, long participants) {
        var key = new GameKey(today(), session.gameType);
        var old = games.getOrDefault(key, new GameTotals(key, 0, 0, 0, 0));
        games.put(key, new GameTotals(key, old.started + started, old.finished + finished,
            old.cancelled + cancelled, old.participants + participants));
    }
    public synchronized Snapshot snapshot() {
        return new Snapshot(List.copyOf(rooms.values()), List.copyOf(games.values()));
    }
}
