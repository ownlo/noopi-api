package com.noopi.realtime;

import com.noopi.room.RoomRuntime;
import java.util.Map;

/** Payloads contain public changes only; secret information is read through /state. */
public interface RoomEvents {
    void publish(RoomRuntime room, String type, Long gameSessionId, Map<String, Object> payload);
    default void game(RoomRuntime room, String type, Map<String, Object> payload) {
        publish(room, type, room.session.id, payload);
    }
    void closePlayer(long roomId, long playerId);
    void closeRoom(long roomId);
}
