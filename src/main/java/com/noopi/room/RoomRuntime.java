package com.noopi.room;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import com.noopi.game.session.GameSessionRuntime;
import java.time.Instant;
import java.util.*;
import static com.noopi.api.ErrorCode.*;

/** All access, including projection and socket membership changes, requires this room's monitor. */
@JsonIgnoreType
public final class RoomRuntime {
    public final long id;
    public final String code;
    public final Instant createdAt;
    public Instant lastActivityAt;
    public final Map<Long, PlayerRuntime> players = new LinkedHashMap<>();
    public final Map<String, Deque<Long>> recentContent = new HashMap<>();
    public long hostPlayerId;
    public boolean closed;
    public GameSessionRuntime session;

    public RoomRuntime(long id, String code, Instant now) {
        this.id = id; this.code = code; this.createdAt = now; this.lastActivityAt = now;
    }
    public PlayerRuntime player(String clientId) {
        return players.values().stream().filter(p -> p.clientId.equals(clientId)).findFirst()
            .orElseThrow(PLAYER_NOT_IN_ROOM::exception);
    }
    public void requireHost(PlayerRuntime player) { NOT_ROOM_HOST.require(player.id == hostPlayerId); }
    public String status() {
        if (closed) return "CLOSED";
        return session != null && session.status == GameSessionRuntime.Status.PLAYING ? "ACTIVE" : "WAITING";
    }
}
