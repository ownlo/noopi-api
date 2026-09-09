package com.noopi.realtime;

import com.noopi.api.DomainException;
import com.noopi.room.RoomStore;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.*;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class RoomHandshakeInterceptor implements HandshakeInterceptor {
    private final RoomStore rooms;
    public RoomHandshakeInterceptor(RoomStore rooms) { this.rooms = rooms; }
    @Override public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                              WebSocketHandler handler, Map<String, Object> attributes) {
        try {
            var query = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams();
            long roomId = Long.parseLong(query.getFirst("roomId"));
            String clientId = query.getFirst("clientId");
            rooms.inRoom(roomId, room -> { room.player(clientId); return null; });
            attributes.put("roomId", roomId);
            attributes.put("clientId", clientId);
            return true;
        } catch (DomainException ex) {
            response.setStatusCode(HttpStatus.valueOf(ex.code().status));
            return false;
        } catch (IllegalArgumentException ex) {
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        }
    }
    @Override public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                         WebSocketHandler handler, Exception exception) {}
}
