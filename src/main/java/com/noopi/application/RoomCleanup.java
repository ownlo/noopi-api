package com.noopi.application;

import com.noopi.realtime.RoomEvents;
import com.noopi.room.*;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RoomCleanup {
    private final RoomStore rooms;
    private final RoomEvents events;
    private final Clock clock;
    private final Duration ttl;
    public RoomCleanup(RoomStore rooms, RoomEvents events, Clock clock,
                       @Value("${noopi.room-ttl:PT12H}") Duration ttl) {
        this.rooms = rooms; this.events = events; this.clock = clock; this.ttl = ttl;
    }
    @Scheduled(fixedDelayString = "${noopi.cleanup-interval-ms:60000}")
    public void clean() {
        for (var room : rooms.snapshot()) {
            synchronized (room) {
                if (room.closed) continue;
                var now = clock.instant();
                if (!now.isBefore(room.lastActivityAt.plus(ttl))) {
                    rooms.remove(room);
                    events.closeRoom(room.id);
                    continue;
                }
            }
        }
    }
}
