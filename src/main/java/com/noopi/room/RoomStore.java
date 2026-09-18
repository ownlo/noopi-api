package com.noopi.room;

import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.random.RandomGenerator;
import org.springframework.stereotype.Component;
import static com.noopi.api.ErrorCode.*;

@Component
public class RoomStore {
    private final ConcurrentHashMap<Long, RoomRuntime> rooms = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> codes = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();
    private final RandomGenerator random;
    private final Clock clock;
    private final com.noopi.metrics.MetricsCollector metrics;
    public RoomStore(RandomGenerator random, Clock clock) {
        this(random, clock, new com.noopi.metrics.MetricsCollector(clock));
    }
    @org.springframework.beans.factory.annotation.Autowired
    public RoomStore(RandomGenerator random, Clock clock, com.noopi.metrics.MetricsCollector metrics) {
        this.random = random; this.clock = clock; this.metrics = metrics;
    }
    public long nextId() { return ids.incrementAndGet(); }

    public RoomRuntime create(PlayerRuntime host) {
        long id = nextId();
        String code;
        do { code = code(); } while (codes.putIfAbsent(code, id) != null);
        RoomRuntime room = new RoomRuntime(id, code, clock.instant());
        room.players.put(host.id, host);
        room.hostPlayerId = host.id;
        metrics.created(room);
        rooms.put(id, room);
        return room;
    }
    private String code() {
        return String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
    }
    public <T> T inRoom(long id, Function<RoomRuntime, T> action) {
        RoomRuntime room = rooms.get(id);
        if (room == null) throw ROOM_NOT_FOUND.exception();
        synchronized (room) {
            ROOM_CLOSED.require(!room.closed);
            try {
                T result = action.apply(room);
                room.lastActivityAt = clock.instant();
                return result;
            } finally {
                metrics.observe(room);
            }
        }
    }
    public long byCode(String code) {
        Long id = codes.get(code);
        if (id == null) throw ROOM_NOT_FOUND.exception();
        return id;
    }
    public Collection<RoomRuntime> snapshot() { return List.copyOf(rooms.values()); }
    /** Caller holds the room monitor. Observe an ended session before detaching it from the Room. */
    public void clearSession(RoomRuntime room) {
        metrics.observe(room);
        room.session = null;
    }
    public void remove(RoomRuntime room) {
        synchronized (room) {
            if (room.closed) return;
            room.closed = true;
            metrics.closed(room);
            rooms.remove(room.id, room);
            codes.remove(room.code, room.id);
        }
    }
}
