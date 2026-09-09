package com.noopi.room;

import java.time.Clock;
import java.util.Collection;
import java.util.List;
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
    public RoomStore(RandomGenerator random, Clock clock) { this.random = random; this.clock = clock; }
    public long nextId() { return ids.incrementAndGet(); }

    public RoomRuntime create(PlayerRuntime host) {
        long id = nextId();
        String code;
        do { code = code(); } while (codes.putIfAbsent(code, id) != null);
        RoomRuntime room = new RoomRuntime(id, code, clock.instant());
        room.players.put(host.id, host);
        room.hostPlayerId = host.id;
        rooms.put(id, room);
        return room;
    }
    private String code() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 6; i++) code.append(alphabet.charAt(random.nextInt(alphabet.length())));
        return code.toString();
    }
    public <T> T inRoom(long id, Function<RoomRuntime, T> action) {
        RoomRuntime room = rooms.get(id);
        if (room == null) throw ROOM_NOT_FOUND.exception();
        synchronized (room) {
            ROOM_CLOSED.require(!room.closed);
            T result = action.apply(room);
            room.lastActivityAt = clock.instant();
            return result;
        }
    }
    public long byCode(String code) {
        Long id = codes.get(code);
        if (id == null) throw ROOM_NOT_FOUND.exception();
        return id;
    }
    public Collection<RoomRuntime> snapshot() { return List.copyOf(rooms.values()); }
    public void remove(RoomRuntime room) {
        room.closed = true;
        rooms.remove(room.id, room);
        codes.remove(room.code, room.id);
    }
}
