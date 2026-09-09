package com.noopi.application;

import com.noopi.game.liar.LiarGameService;
import com.noopi.realtime.RoomEvents;
import com.noopi.room.*;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RoomCleanup {
    private final RoomStore rooms;
    private final RoomEvents events;
    private final LiarGameService liar;
    private final Clock clock;
    private final Duration ttl;
    private final Duration grace;
    public RoomCleanup(RoomStore rooms, RoomEvents events, LiarGameService liar, Clock clock,
                       @Value("${noopi.room-ttl:PT12H}") Duration ttl,
                       @Value("${noopi.disconnect-grace:PT2M}") Duration grace) {
        this.rooms = rooms; this.events = events; this.liar = liar; this.clock = clock; this.ttl = ttl; this.grace = grace;
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
                var host = room.players.get(room.hostPlayerId);
                if (host != null && expired(host)) {
                    room.players.values().stream().filter(p -> p.connectionStatus == PlayerRuntime.ConnectionStatus.CONNECTED)
                        .findFirst().ifPresent(p -> {
                            room.hostPlayerId = p.id;
                            events.publish(room, "HOST_CHANGED", null, Map.of("hostPlayerId", p.id));
                        });
                }
                if (room.session != null && !room.session.ended()) {
                    for (var p : room.players.values()) {
                        if (expired(p)) liar.cancelIfLiarExpired(room, p.id);
                    }
                }
            }
        }
    }
    private boolean expired(PlayerRuntime p) {
        return p.connectionStatus == PlayerRuntime.ConnectionStatus.DISCONNECTED && p.disconnectedAt != null
            && !clock.instant().isBefore(p.disconnectedAt.plus(grace));
    }
}
