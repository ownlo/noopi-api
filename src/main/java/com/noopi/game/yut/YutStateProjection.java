package com.noopi.game.yut;

import com.noopi.room.RoomRuntime;
import java.util.*;
import org.springframework.stereotype.Component;
import static com.noopi.game.yut.YutGameRuntime.*;

@Component
public class YutStateProjection {
    private final YutGameService service;
    public YutStateProjection(YutGameService service) { this.service = service; }
    public Map<String, Object> project(RoomRuntime room, long requester) {
        var game = (YutGameRuntime) room.session.game;
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("type", "YUT"); state.put("phase", game.phase.name()); state.put("mode", game.mode.name());
        if (game.phase == Phase.CANCELLED) { state.put("reason", game.cancelReason); return state; }
        if (game.mode == Mode.TEAM) state.put("teams", teams(room));
        if (game.phase == Phase.TEAM_SELECT) {
            var mine = game.teams.get(requester);
            state.put("myTeam", mine == null ? null : mine.name());
            state.put("selectableTeams", Arrays.stream(Team.values()).filter(team -> game.teams.entrySet().stream()
                .filter(e -> room.players.containsKey(e.getKey()) && e.getValue() == team && e.getKey() != requester).count() < 2).map(Enum::name).toList());
            state.put("canStart", requester == room.hostPlayerId && service.teamsReady(room));
        }
        if (game.phase == Phase.PLAYING) {
            state.put("turn", Map.of("turnNo", game.turnNo, "currentPlayerId", game.currentPlayer(), "turnPhase", game.turnPhase.name(),
                "throwResults", List.copyOf(game.throwResults), "moveTokens", List.copyOf(game.tokens.values()), "pendingBonusThrows", game.pendingBonusThrows));
            state.put("pieces", game.pieces.values().stream().map(piece -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("pieceId", piece.id); item.put("ownerType", game.mode == Mode.TEAM ? "TEAM" : "PLAYER");
                item.put("ownerId", piece.ownerId); item.put("status", piece.status.name()); item.put("nodeId", piece.nodeId);
                item.put("groupPieceIds", piece.group); return item;
            }).toList());
            state.put("finishedPieceCounts", game.pieces.values().stream().map(p -> p.ownerId).distinct()
                .map(owner -> Map.of("ownerId", owner, "count", game.pieces.values().stream()
                    .filter(p -> p.ownerId.equals(owner) && p.status == PieceStatus.FINISHED).count())).toList());
            state.put("myAction", room.session.active(requester) && game.currentPlayer() == requester ? action(game) : null);
        }
        if (game.phase == Phase.FINISHED) {
            if (game.mode == Mode.TEAM) state.put("winnerTeam", teams(room).stream().filter(t -> t.get("team").equals(game.winnerOwner)).findFirst().orElseThrow());
            else state.put("winnerPlayer", player(room, Long.parseLong(game.winnerOwner)));
        }
        return state;
    }
    private Map<String, Object> action(YutGameRuntime game) {
        if (game.turnPhase == TurnPhase.WAITING_THROW) return Map.of("type", "THROW_YUT");
        if (game.turnPhase == TurnPhase.WAITING_PATH_SELECTION) return Map.of("type", "SELECT_PATH", "pieceId", game.selectedPiece,
            "moveTokenId", game.selectedToken, "eligiblePathIds", YutBoard.paths(game.pieces.get(game.selectedPiece)));
        if (game.selectedToken != null) return Map.of("type", "SELECT_PIECE", "moveTokenId", game.selectedToken, "eligiblePieceIds", service.eligiblePieces(game));
        return Map.of("type", "SELECT_MOVE_TOKEN", "moveTokenIds", List.copyOf(game.tokens.keySet()));
    }
    private Map<String, Object> player(RoomRuntime room, long id) {
        var participant = room.session.participants().get(id);
        return Map.of("playerId", id, "nickname", participant == null ? room.players.get(id).nickname : participant.nickname());
    }
    private List<Map<String, Object>> teams(RoomRuntime room) {
        var game = (YutGameRuntime) room.session.game;
        return Arrays.stream(Team.values()).map(team -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("team", team.name()); result.put("name", team == Team.NOOPI ? "누피팀" : "데이팀"); result.put("capacity", 2);
            result.put("players", game.teams.entrySet().stream().filter(e -> e.getValue() == team &&
                (room.session.participants().containsKey(e.getKey()) || room.players.containsKey(e.getKey())))
                .map(e -> player(room, e.getKey())).toList()); return result;
        }).toList();
    }
}
