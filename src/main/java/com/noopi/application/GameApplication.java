package com.noopi.application;

import com.noopi.api.Responses;
import com.noopi.game.blind.BlindGameService;
import com.noopi.game.blind.BlindStateProjection;
import com.noopi.game.liar.LiarGameService;
import com.noopi.game.liar.LiarStateProjection;
import com.noopi.game.mafia.MafiaGameService;
import com.noopi.game.mafia.MafiaStateProjection;
import com.noopi.game.session.GameSessionRuntime;
import com.noopi.realtime.RoomEvents;
import com.noopi.room.*;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import static com.noopi.api.ErrorCode.*;

@Service
public class GameApplication {
    private final RoomStore rooms;
    private final LiarGameService liar;
    private final LiarStateProjection projection;
    private final BlindGameService blind;
    private final BlindStateProjection blindProjection;
    private final MafiaGameService mafia;
    private final MafiaStateProjection mafiaProjection;
    private final RoomEvents events;
    private final Clock clock;
    private final Duration disconnectGrace;
    @Autowired
    public GameApplication(RoomStore rooms, LiarGameService liar, LiarStateProjection projection,
                           BlindGameService blind, BlindStateProjection blindProjection,
                           MafiaGameService mafia, MafiaStateProjection mafiaProjection, RoomEvents events,
                           Clock clock, @Value("${noopi.disconnect-grace:PT2M}") Duration disconnectGrace) {
        this.rooms = rooms; this.liar = liar; this.projection = projection;
        this.blind = blind; this.blindProjection = blindProjection;
        this.mafia = mafia; this.mafiaProjection = mafiaProjection;
        this.events = events; this.clock = clock; this.disconnectGrace = disconnectGrace;
    }
    public GameApplication(RoomStore rooms, LiarGameService liar, LiarStateProjection projection,
                           BlindGameService blind, BlindStateProjection blindProjection,
                           RoomEvents events, Clock clock, Duration disconnectGrace) {
        this.rooms=rooms; this.liar=liar; this.projection=projection; this.blind=blind; this.blindProjection=blindProjection;
        this.mafia=new MafiaGameService(new java.util.Random(0),events); this.mafiaProjection=new MafiaStateProjection(this.mafia);
        this.events=events; this.clock=clock; this.disconnectGrace=disconnectGrace;
    }
    public Responses.CreatedRoom create(String clientId, String nickname, String gender) {
        var p = new PlayerRuntime(rooms.nextId(), clientId, nickname, gender);
        var room = rooms.create(p);
        return new Responses.CreatedRoom(new Responses.Room(room.id, room.code, "WAITING"), player(room, p));
    }
    public Responses.Lookup lookup(String clientId, String code) {
        requireClient(clientId);
        return rooms.inRoom(rooms.byCode(code), r -> {
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
                r.session.status.name(), project(r, me.id));
            return new Responses.State(new Responses.RoomState(r.id, r.code, r.status(), r.hostPlayerId), player(r, me), players, session);
        });
    }
    public Responses.Session createSessionConfigured(long roomId, String clientId, String type, Map<String, Object> config) {
        return rooms.inRoom(roomId, r -> {
            r.requireHost(r.player(clientId));
            ACTIVE_GAME_SESSION_EXISTS.require(r.session == null || r.session.ended());
            UNSUPPORTED_GAME_TYPE.require("LIAR".equals(type) || "BLIND".equals(type) || "MAFIA".equals(type));
            if ("BLIND".equals(type)) blind.requirePlayerCount(r);
            Object categoryValue = config == null ? null : config.get("categoryCode");
            String category = categoryValue instanceof String value ? value : null;
            INVALID_GAME_CONFIG.require("LIAR".equals(type)
                ? config != null && config.size() == 1 && category != null && !category.isBlank()
                : config != null && config.isEmpty());
            var runtime = "LIAR".equals(type) ? liar.prepare(category) : "BLIND".equals(type) ? blind.prepare() : mafia.prepare();
            r.session = new GameSessionRuntime(rooms.nextId(), type, runtime);
            events.game(r, "GAME_SESSION_CREATED", Map.of("gameType", type));
            return new Responses.Session(r.session.id, type, r.session.status.name());
        });
    }
    public void start(long roomId, long sessionId, String clientId) {
        rooms.inRoom(roomId, r -> {
            r.requireHost(r.player(clientId)); session(r, sessionId);
            if ("LIAR".equals(r.session.gameType)) liar.start(r); else if ("BLIND".equals(r.session.gameType)) blind.start(r); else mafia.start(r);
            return null;
        });
    }
    public void cancel(long roomId, long sessionId, String clientId) {
        rooms.inRoom(roomId, r -> {
            r.requireHost(r.player(clientId)); session(r, sessionId);
            if ("LIAR".equals(r.session.gameType)) liar.cancel(r, "HOST_CANCELLED"); else if ("BLIND".equals(r.session.gameType)) blind.cancel(r, "HOST_CANCELLED"); else mafia.cancel(r, "HOST_CANCELLED");
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
    public Responses.Session createSession(long roomId, String clientId, String type, String category) {
        return createSessionConfigured(roomId, clientId, type,
            "LIAR".equals(type) ? (category == null ? null : Map.of("categoryCode", category)) : Map.of());
    }
    public Responses.Guess blindGuess(long roomId, long sessionId, String clientId, String answer) {
        return rooms.inRoom(roomId, r -> {
            var p = r.player(clientId); session(r, sessionId);
            requireType(r, "BLIND");
            return new Responses.Guess(blind.guess(r, p.id, answer));
        });
    }
    public void mafiaRoleCheck(long roomId, long sessionId, String clientId) {
        rooms.inRoom(roomId, r -> { var p=r.player(clientId); session(r,sessionId); requireType(r,"MAFIA"); mafia.roleCheck(r,p.id); return null; });
    }
    public Map<String,Object> mafiaNightAction(long roomId,long sessionId,String clientId,String type,Long target) {
        return rooms.inRoom(roomId,r->{var p=r.player(clientId);session(r,sessionId);requireType(r,"MAFIA");return mafia.nightAction(r,p.id,type,target);});
    }
    public Responses.VoteRound startMafiaVote(long roomId,long sessionId,String clientId) {
        return rooms.inRoom(roomId,r->{var p=r.player(clientId);r.requireHost(p);session(r,sessionId);requireType(r,"MAFIA");return new Responses.VoteRound(mafia.startVote(r,p.id));});
    }
    public void mafiaVote(long roomId,long sessionId,String clientId,Long round,Long target) {
        rooms.inRoom(roomId,r->{var p=r.player(clientId);session(r,sessionId);requireType(r,"MAFIA");mafia.vote(r,p.id,round,target);return null;});
    }
    public void mafiaJudgment(long roomId,long sessionId,String clientId,String choice) {
        rooms.inRoom(roomId,r->{var p=r.player(clientId);session(r,sessionId);requireType(r,"MAFIA");mafia.judgment(r,p.id,choice);return null;});
    }
    public void advanceMafia(long roomId,long sessionId,String clientId) {
        rooms.inRoom(roomId,r->{var p=r.player(clientId);r.requireHost(p);session(r,sessionId);requireType(r,"MAFIA");mafia.advance(r,p.id);return null;});
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
    private Map<String, Object> project(RoomRuntime room, long requester) {
        return "LIAR".equals(room.session.gameType) ? projection.project(room.session, requester)
            : "BLIND".equals(room.session.gameType) ? blindProjection.project(room.session, requester)
            : mafiaProjection.project(room, requester);
    }
    private static void requireClient(String id) { PLAYER_NOT_IN_ROOM.require(id != null && !id.isBlank()); }
    private Responses.Player player(RoomRuntime r, PlayerRuntime p) {
        return new Responses.Player(p.id, p.nickname, p.gender.name(), r.hostPlayerId == p.id, p.connectionStatus.name());
    }
    private void reconnect(RoomRuntime room, PlayerRuntime player) {
        if (player.reconnect()) events.publish(room, "PLAYER_RECONNECTED", null, Map.of("playerId", player.id));
    }
}
