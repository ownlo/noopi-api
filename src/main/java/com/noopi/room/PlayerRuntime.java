package com.noopi.room;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import static com.noopi.api.ErrorCode.*;

@JsonIgnoreType
public final class PlayerRuntime {
    public enum Gender { MALE, FEMALE }
    public enum ConnectionStatus { CONNECTED, DISCONNECTED }
    public final long id;
    public final String clientId;
    public final String nickname;
    public final Gender gender;
    public ConnectionStatus connectionStatus = ConnectionStatus.CONNECTED;
    public Instant disconnectedAt;
    public final Set<String> connections = new HashSet<>();

    public PlayerRuntime(long id, String clientId, String nickname, String gender) {
        PLAYER_NOT_IN_ROOM.require(clientId != null && !clientId.isBlank());
        INVALID_NICKNAME.require(nickname != null);
        String trimmed = nickname.strip();
        int length = trimmed.codePointCount(0, trimmed.length());
        INVALID_NICKNAME.require(length >= 1 && length <= 5);
        INVALID_GENDER.require("MALE".equals(gender) || "FEMALE".equals(gender));
        this.id = id;
        this.clientId = clientId;
        this.nickname = trimmed;
        this.gender = Gender.valueOf(gender);
    }

    public boolean reconnect() {
        boolean changed = connectionStatus == ConnectionStatus.DISCONNECTED;
        connectionStatus = ConnectionStatus.CONNECTED;
        disconnectedAt = null;
        return changed;
    }
}
