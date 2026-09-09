package com.noopi.realtime;

import com.noopi.api.DomainException;
import com.noopi.room.*;
import java.time.Clock;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class RoomSocketHandler extends TextWebSocketHandler {
    private final RoomStore rooms;
    private final WebSocketRoomEvents events;
    private final Clock clock;
    public RoomSocketHandler(RoomStore rooms, WebSocketRoomEvents events, Clock clock) {
        this.rooms = rooms; this.events = events; this.clock = clock;
    }
    @Override public void afterConnectionEstablished(WebSocketSession socket) throws Exception {
        long roomId = (long) socket.getAttributes().get("roomId");
        String clientId = (String) socket.getAttributes().get("clientId");
        try {
            rooms.inRoom(roomId, room -> {
                // Recheck under the lock: the player may have left after the handshake.
                var p = room.player(clientId);
                socket.getAttributes().put("playerId", p.id);
                events.add(room.id, p.id, socket);
                p.connections.add(socket.getId());
                if (p.reconnect()) events.publish(room, "PLAYER_RECONNECTED", null, Map.of("playerId", p.id));
                return null;
            });
        } catch (DomainException ex) { socket.close(CloseStatus.POLICY_VIOLATION); }
    }
    @Override public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) {
        Long roomId = (Long) socket.getAttributes().get("roomId");
        Long playerId = (Long) socket.getAttributes().get("playerId");
        if (roomId == null) return;
        try {
            rooms.inRoom(roomId, room -> {
                events.remove(roomId, socket.getId());
                var p = room.players.get(playerId);
                if (p != null && p.connections.remove(socket.getId()) && p.connections.isEmpty()) {
                    p.connectionStatus = PlayerRuntime.ConnectionStatus.DISCONNECTED;
                    p.disconnectedAt = clock.instant();
                    events.publish(room, "PLAYER_DISCONNECTED", null, Map.of("playerId", p.id));
                }
                return null;
            });
        } catch (DomainException ignored) { events.remove(roomId, socket.getId()); }
    }
    @Override public void handleTransportError(WebSocketSession socket, Throwable error) throws Exception {
        socket.close(CloseStatus.SERVER_ERROR);
        afterConnectionClosed(socket, CloseStatus.SERVER_ERROR);
    }
    @Override protected void handleTextMessage(WebSocketSession socket, TextMessage message) throws Exception {
        // There is no client subscription/action protocol. A connection belongs to exactly one room.
        socket.close(CloseStatus.POLICY_VIOLATION);
    }
}
