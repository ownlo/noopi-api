package com.noopi.application;

import com.noopi.api.Responses;
import com.noopi.game.blind.BlindGameService;
import com.noopi.game.blind.BlindStateProjection;
import com.noopi.game.liar.LiarGameService;
import com.noopi.game.liar.LiarStateProjection;
import com.noopi.game.session.GameSessionRuntime;
import com.noopi.realtime.RoomEvents;
import com.noopi.room.*;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import static com.noopi.api.ErrorCode.*;

@Service
public class GameApplication {
    private final RoomStore rooms;
    private final LiarGameService liar;
    private final LiarStateProjection projection;
    private final BlindGameService blind;
    private final BlindStateProjection blindProjection;
    private final RoomEvents events;
    private final Clock clock;
    private final Duration disconnectGrace;
    public GameApplication(RoomStore rooms, LiarGameService liar, LiarStateProjection projection,
                           BlindGameService blind, BlindStateProjection blindProjection,
                           RoomEvents events, Clock clock, @Value("${noopi.disconnect-grace:PT2M}") Duration disconnectGrace) {
        this.rooms = rooms; this.liar = liar; this.projection = projection;
        this.blind = blind; this.blindProjection = blindProjection;
        this.events = events; this.clock = clock; this.disconnectGrace = disconnectGrace;
    }
    public Responses.CreatedRoom create(String clientId, String nickname, String gender) {
        var p = new PlayerRuntime(rooms.nextId(), clientId, nickname, gender);
        var room = rooms.create(p);
        return new Responses.CreatedRoom(new Responses.Room(room.id, room.code, "WAITING"), player(room, p));
    }
    public Responses.Lookup lookup(String clientId, String code) {
        requireClient(clientId);
        return rooms.inRoom(rooms.byCode(code), r -> {
            requireConnectedHost(r);
            return new Responses.Lookup(r.id, r.code, r.status(), r.players.size(), true);
        });
    }
    public Responses.Player join(long roomId, String clientId, String nickname, String gender) {
        requireClient(clientId);
        return rooms.inRoom(roomId, r -> {
            var existing = r.players.values().stream().filter(p -> p.clientId.equals(clientId)).findFirst();
            if (existing.isPresent()) {
                reconnect(r, existing.get());
                return player(r, existing.get());
            }
            requireConnectedHost(r);
            var p = new PlayerRuntime(rooms.nextId(), clientId, nickname, gender);
            NICKNAME_ALREADY_EXISTS.require(r.players.values().stream().noneMatch(other -> other.nickname.equals(p.nickname)));
            r.players.put(p.id, p);
            events.publish(r, "PLAYER_JOINED", null, Map.of("playerId", p.id, "nickname", p.nickname));
            return player(r, p);
        });
    }
    public void leave(long roomId, String clientId) {
        rooms.inRoom(roomId, r -> {
            var p = r.player(clientId);
            if (r.session != null && "LIAR".equals(r.session.gameType)) liar.playerLeft(r, p.id);
            r.players.remove(p.id);
            events.closePlayer(r.id, p.id);
            events.publish(r, "PLAYER_LEFT", null, Map.of("playerId", p.id));
            if (r.hostPlayerId == p.id) {
                events.publish(r, "ROOM_CLOSED", null, Map.of("reason", "HOST_LEFT"));
                rooms.remove(r);
                events.closeRoom(r.id);
            }
            return null;
        });
    }
    public Responses.State state(long roomId, String clientId) {
        return rooms.inRoom(roomId, r -> {
            var me = r.player(clientId);
            var players = r.players.values().stream().map(p -> new Responses.RoomPlayer(p.id, p.nickname,
                p.gender.name(), p.id == r.hostPlayerId, p.connectionStatus.name(), r.session != null && r.session.active(p.id))).toList();
            var session = r.session == null ? null : new Responses.SessionState(r.session.id, r.session.gameType,
                r.session.status.name(), project(r.session, me.id));
            return new Responses.State(new Responses.RoomState(r.id, r.code, r.status(), r.hostPlayerId), player(r, me), players, session);
        });
    }
    public Responses.Session createSession(long roomId, String clientId, String type, String category) {
        return rooms.inRoom(roomId, r -> {
            r.requireHost(r.player(clientId));
            ACTIVE_GAME_SESSION_EXISTS.require(r.session == null || r.session.ended());
            UNSUPPORTED_GAME_TYPE.require("LIAR".equals(type) || "BLIND".equals(type));
            if ("BLIND".equals(type)) blind.requirePlayerCount(r);
            INVALID_GAME_CONFIG.require("LIAR".equals(type) ? category != null && !category.isBlank() : category == null);
            var runtime = "LIAR".equals(type) ? liar.prepare(category) : blind.prepare();
            r.session = new GameSessionRuntime(rooms.nextId(), type, runtime);
            events.game(r, "GAME_SESSION_CREATED", Map.of("gameType", type));
            return new Responses.Session(r.session.id, type, r.session.status.name());
        });
    }
    public void start(long roomId, long sessionId, String clientId) {
        rooms.inRoom(roomId, r -> {
            r.requireHost(r.player(clientId)); session(r, sessionId);
            if ("LIAR".equals(r.session.gameType)) liar.start(r); else blind.start(r);
            return null;
        });
    }
    public void cancel(long roomId, long sessionId, String clientId) {
        rooms.inRoom(roomId, r -> {
            r.requireHost(r.player(clientId)); session(r, sessionId);
            if ("LIAR".equals(r.session.gameType)) liar.cancel(r, "HOST_CANCELLED"); else blind.cancel(r, "HOST_CANCELLED");
            return null;
        });
    }
    public void roleCheck(long roomId, long sessionId, String clientId) {
        rooms.inRoom(roomId, r -> { var p = r.player(clientId); session(r, sessionId); requireType(r, "LIAR"); liar.roleCheck(r, p.id); return null; });
    }
    public Responses.VoteRound startVote(long roomId, long sessionId, String clientId) {
        return rooms.inRoom(roomId, r -> {
            var p = r.player(clientId); r.requireHost(p); session(r, sessionId); requireType(r, "LIAR");
            return new Responses.VoteRound(liar.startVote(r, p.id));
        });
    }
    public void vote(long roomId, long sessionId, String clientId, Long round, Long target) {
        rooms.inRoom(roomId, r -> { var p = r.player(clientId); session(r, sessionId); requireType(r, "LIAR"); liar.vote(r, p.id, round, target); return null; });
    }
    public Responses.Guess guess(long roomId, long sessionId, String clientId, String answer) {
        return rooms.inRoom(roomId, r -> {
            var p = r.player(clientId); session(r, sessionId); requireType(r, "LIAR"); return new Responses.Guess(liar.guess(r, p.id, answer));
        });
    }
    public Responses.Guess blindGuess(long roomId, long sessionId, String clientId, String answer) {
        return rooms.inRoom(roomId, r -> {
            var p = r.player(clientId); session(r, sessionId);
            requireType(r, "BLIND");
            return new Responses.Guess(blind.guess(r, p.id, answer));
        });
    }
    public void exclude(long roomId, long sessionId, String clientId, long playerId) {
        rooms.inRoom(roomId, r -> {
            r.requireHost(r.player(clientId)); session(r, sessionId); requireType(r, "LIAR"); r.session.requireParticipant(playerId);
            var p = r.players.get(playerId);
            PLAYER_NOT_IN_GAME.require(p != null);
            PLAYER_NOT_DISCONNECTED.require(p.connectionStatus == PlayerRuntime.ConnectionStatus.DISCONNECTED);
            PLAYER_NOT_EXCLUDABLE.require(liar.canExclude(r) && p.disconnectedAt != null
                && !clock.instant().isBefore(p.disconnectedAt.plus(disconnectGrace)));
            liar.removeParticipant(r, playerId);
            return null;
        });
    }
    private void session(RoomRuntime room, long id) {
        GAME_SESSION_NOT_FOUND.require(room.session != null && room.session.id == id);
    }
    private void requireType(RoomRuntime room, String type) {
        INVALID_GAME_PHASE.require(type.equals(room.session.gameType));
    }
    private Map<String, Object> project(GameSessionRuntime session, long requester) {
        return "LIAR".equals(session.gameType) ? projection.project(session, requester)
            : blindProjection.project(session, requester);
    }
    private static void requireClient(String id) { PLAYER_NOT_IN_ROOM.require(id != null && !id.isBlank()); }
    private static void requireConnectedHost(RoomRuntime room) {
        var host = room.players.get(room.hostPlayerId);
        ROOM_NOT_FOUND.require(host != null && host.connectionStatus == PlayerRuntime.ConnectionStatus.CONNECTED);
    }
    private Responses.Player player(RoomRuntime r, PlayerRuntime p) {
        return new Responses.Player(p.id, p.nickname, p.gender.name(), r.hostPlayerId == p.id, p.connectionStatus.name());
    }
    private void reconnect(RoomRuntime room, PlayerRuntime player) {
        if (player.reconnect()) events.publish(room, "PLAYER_RECONNECTED", null, Map.of("playerId", player.id));
    }
}
