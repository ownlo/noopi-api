package com.noopi.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noopi.room.RoomRuntime;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

@Component
public class WebSocketRoomEvents implements RoomEvents {
    public record Event(String eventId, String type, long roomId, Long gameSessionId, Instant occurredAt, Map<String, Object> payload) {}
    private record Connection(long playerId, WebSocketSession socket) {}
    private static final Logger log = LoggerFactory.getLogger(WebSocketRoomEvents.class);
    private final Map<Long, Map<String, Connection>> connections = new ConcurrentHashMap<>();
    private final ObjectMapper json;
    private final Clock clock;
    public WebSocketRoomEvents(ObjectMapper json, Clock clock) { this.json = json; this.clock = clock; }

    // Membership validation and these operations share the room monitor with HTTP mutations.
    public void add(long roomId, long playerId, WebSocketSession session) {
        connections.computeIfAbsent(roomId, id -> new ConcurrentHashMap<>()).put(session.getId(),
            new Connection(playerId, new ConcurrentWebSocketSessionDecorator(session, 2000, 65536)));
    }
    public void remove(long roomId, String connectionId) {
        var room = connections.get(roomId);
        if (room != null) {
            room.remove(connectionId);
            if (room.isEmpty()) connections.remove(roomId, room);
        }
    }
    public void publish(RoomRuntime room, String type, Long gameSessionId, Map<String, Object> payload) {
        var sockets = connections.get(room.id);
        if (sockets == null || sockets.isEmpty()) return;
        TextMessage message;
        try {
            message = new TextMessage(json.writeValueAsString(new Event(UUID.randomUUID().toString(), type,
                room.id, gameSessionId, clock.instant(), Map.copyOf(payload))));
        } catch (Exception ex) {
            log.error("Event serialization failed: type={}", type);
            return;
        }
        for (Connection connection : List.copyOf(sockets.values())) {
            try {
                if (connection.socket.isOpen()) connection.socket.sendMessage(message);
            } catch (Exception ex) {
                log.atWarn().addKeyValue("roomId", room.id).addKeyValue("playerId", connection.playerId)
                    .log("WebSocket send failed");
                close(connection.socket);
            }
        }
    }
    public void closePlayer(long roomId, long playerId) {
        var sockets = connections.get(roomId);
        if (sockets == null) return;
        for (var entry : List.copyOf(sockets.entrySet())) {
            if (entry.getValue().playerId == playerId) {
                sockets.remove(entry.getKey());
                close(entry.getValue().socket);
            }
        }
        if (sockets.isEmpty()) connections.remove(roomId, sockets);
    }
    public void closeRoom(long roomId) {
        var sockets = connections.remove(roomId);
        if (sockets != null) sockets.values().forEach(c -> close(c.socket));
    }
    private void close(WebSocketSession socket) {
        try { socket.close(CloseStatus.NORMAL); } catch (Exception ignored) { /* Already disconnected. */ }
    }
}
