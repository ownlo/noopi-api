package com.noopi.game.yut;

import com.noopi.game.session.GameSessionRuntime;
import com.noopi.game.session.GameSessionRuntime.Participant;
import com.noopi.realtime.RoomEvents;
import com.noopi.room.*;
import java.util.*;
import java.util.random.RandomGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import static com.noopi.api.ErrorCode.*;
import static com.noopi.game.yut.YutGameRuntime.*;

/** Every method is called inside RoomStore.inRoom's atomic boundary. */
@Service
public class YutGameService {
    static final double NAK_PROBABILITY = 0.05;
    private final RandomGenerator random;
    private final RoomEvents events;
    private final double nakProbability;
    @Autowired
    public YutGameService(RandomGenerator random, RoomEvents events) { this(random, events, NAK_PROBABILITY); }
    private YutGameService(RandomGenerator random, RoomEvents events, double nakProbability) {
        this.random = random; this.events = events; this.nakProbability = nakProbability;
    }
    public static YutGameService withNakProbability(RandomGenerator random, RoomEvents events, double nakProbability) {
        return new YutGameService(random, events, nakProbability);
    }
    public YutGameRuntime prepare(Object mode) {
        INVALID_GAME_CONFIG.require("INDIVIDUAL".equals(mode) || "TEAM".equals(mode));
        return new YutGameRuntime(Mode.valueOf((String) mode));
    }
    private YutGameRuntime game(RoomRuntime room) { return (YutGameRuntime) room.session.game; }
    public void selectTeam(RoomRuntime room, long player, String teamName) {
        var game = game(room);
        GAME_ALREADY_STARTED.require(room.session.status == GameSessionRuntime.Status.READY);
        INVALID_GAME_PHASE.require(game.phase == Phase.TEAM_SELECT);
        NOT_GAME_PARTICIPANT.require(room.players.containsKey(player));
        INVALID_TEAM.require("NOOPI".equals(teamName) || "DAY".equals(teamName));
        game.teams.keySet().retainAll(room.players.keySet());
        Team team = Team.valueOf(teamName);
        if (game.teams.get(player) == team) return;
        TEAM_FULL.require(game.teams.values().stream().filter(t -> t == team).count() < 2);
        game.teams.put(player, team);
        events.game(room, "YUT_TEAM_CHANGED", Map.of("playerId", player, "team", team.name()));
    }
    public boolean teamsReady(RoomRuntime room) {
        var game = game(room);
        var players = connected(room);
        return players.size() == 4 && Arrays.stream(Team.values()).allMatch(team ->
            players.stream().filter(p -> game.teams.get(p.playerId()) == team).count() == 2);
    }
    private List<Participant> connected(RoomRuntime room) {
        return room.players.values().stream().filter(p -> p.connectionStatus == PlayerRuntime.ConnectionStatus.CONNECTED)
            .map(p -> new Participant(p.id, p.nickname)).toList();
    }
    public void start(RoomRuntime room) {
        GAME_SESSION_NOT_READY.require(room.session.status == GameSessionRuntime.Status.READY);
        var game = game(room);
        var players = connected(room);
        NOT_ENOUGH_PLAYERS.require(players.size() >= (game.mode == Mode.TEAM ? 4 : 2));
        TOO_MANY_PLAYERS.require(players.size() <= 4);
        if (game.mode == Mode.TEAM) INVALID_GAME_CONFIG.require(teamsReady(room));
        var ids = new ArrayList<>(players.stream().map(Participant::playerId).toList());
        for (int i = ids.size() - 1; i > 0; i--) Collections.swap(ids, i, random.nextInt(i + 1));
        if (game.mode == Mode.TEAM) {
            Team first = game.teams.get(ids.getFirst());
            var one = ids.stream().filter(id -> game.teams.get(id) == first).toList();
            var two = ids.stream().filter(id -> game.teams.get(id) != first).toList();
            game.turnOrder.addAll(List.of(one.get(0), two.get(0), one.get(1), two.get(1)));
            game.teams.keySet().retainAll(ids);
        } else game.turnOrder.addAll(ids);
        var owners = game.mode == Mode.TEAM ? List.of("NOOPI", "DAY") : players.stream().map(p -> Long.toString(p.playerId())).toList();
        for (String owner : owners) for (int i = 1; i <= 4; i++) {
            var piece = new Piece(owner + "-" + i, owner);
            game.pieces.put(piece.id, piece);
        }
        room.session.start(players);
        game.phase = Phase.PLAYING;
        events.game(room, "GAME_STARTED", Map.of("gameType", "YUT"));
        turnEvent(room);
    }
    private YutGameRuntime requireTurn(RoomRuntime room, long player) {
        NOT_GAME_PARTICIPANT.require(room.session.active(player));
        GAME_SESSION_ALREADY_FINISHED.require(!room.session.ended());
        var game = game(room);
        INVALID_GAME_PHASE.require(game.phase == Phase.PLAYING);
        NOT_CURRENT_TURN.require(game.currentPlayer() == player);
        return game;
    }
    public record ThrowResult(Result result, int steps, String moveTokenId, boolean bonusThrowGranted) {}
    public ThrowResult throwYut(RoomRuntime room, long player) {
        var game = requireTurn(room, player);
        INVALID_TURN_PHASE.require(game.turnPhase == TurnPhase.WAITING_THROW && game.selectedToken == null);
        if (random.nextDouble() < nakProbability) {
            game.lastThrow = new LastThrow(++game.throwSequence, game.turnNo, player, Result.NAK, 0, false);
            events.game(room, "YUT_THROW_RESOLVED", Map.of("playerId", player, "result", Result.NAK.name(), "steps", 0, "bonusThrowGranted", false));
            game.pendingBonusThrows = 0;
            game.selectedToken = null;
            game.selectedPiece = null;
            if (game.tokens.isEmpty()) {
                advanceTurn(room);
                turnEvent(room);
            } else game.turnPhase = TurnPhase.WAITING_MOVE;
            return new ThrowResult(Result.NAK, 0, null, false);
        }
        boolean[] faces = new boolean[4];
        int fronts = 0;
        for (int i = 0; i < faces.length; i++) {
            faces[i] = random.nextBoolean();
            if (faces[i]) fronts++;
        }
        Result result = switch (fronts) {
            case 0 -> Result.MO;
            case 1 -> faces[0] ? Result.BACK_DO : Result.DO;
            case 2 -> Result.GAE;
            case 3 -> Result.GEOL;
            default -> Result.YUT;
        };
        if (game.pendingBonusThrows > 0) game.pendingBonusThrows--;
        if (result.bonus()) game.pendingBonusThrows++;
        var token = new MoveToken("mt-" + room.session.id + "-" + (++game.nextToken), result, result.steps);
        game.tokens.put(token.moveTokenId(), token);
        game.throwResults.add(result);
        game.lastThrow = new LastThrow(++game.throwSequence, game.turnNo, player, result, result.steps, result.bonus());
        game.turnPhase = game.pendingBonusThrows > 0 ? TurnPhase.WAITING_THROW : TurnPhase.WAITING_MOVE;
        events.game(room, "YUT_THROW_RESOLVED", Map.of("playerId", player, "result", result.name(), "steps", result.steps, "bonusThrowGranted", result.bonus()));
        return new ThrowResult(result, result.steps, token.moveTokenId(), result.bonus());
    }
    public List<String> eligiblePieces(YutGameRuntime game) {
        String owner = game.owner(game.currentPlayer());
        MoveToken selected = game.selectedToken == null ? null : game.tokens.get(game.selectedToken);
        boolean backDo = selected != null && selected.result() == Result.BACK_DO;
        return game.pieces.values().stream().filter(p -> p.ownerId.equals(owner)
                && (backDo ? p.status == PieceStatus.ON_BOARD : p.status != PieceStatus.FINISHED)
                && p.id.equals(p.group.getFirst()))
            .map(p -> p.id).toList();
    }
    public void selectToken(RoomRuntime room, long player, String tokenId) {
        var game = requireTurn(room, player);
        MOVE_TOKEN_ALREADY_USED.require(!game.usedTokens.contains(tokenId));
        MOVE_TOKEN_NOT_FOUND.require(game.tokens.containsKey(tokenId));
        INVALID_TURN_PHASE.require(game.turnPhase == TurnPhase.WAITING_MOVE);
        ACTION_ALREADY_PROCESSED.require(game.selectedToken == null);
        game.selectedToken = tokenId;
        if (game.tokens.get(tokenId).result() == Result.BACK_DO && eligiblePieces(game).isEmpty()) {
            consumeSelectedToken(room);
        }
    }
    public MoveResult selectPiece(RoomRuntime room, long player, String pieceId) {
        var game = requireTurn(room, player);
        INVALID_TURN_PHASE.require(game.turnPhase == TurnPhase.WAITING_MOVE && game.selectedToken != null);
        PIECE_NOT_ELIGIBLE.require(eligiblePieces(game).contains(pieceId));
        var piece = game.pieces.get(pieceId);
        var token = game.tokens.get(game.selectedToken);
        if (token.result() == Result.BACK_DO) {
            game.selectedPiece = pieceId;
            return move(room, player, piece.route);
        }
        var paths = YutBoard.paths(piece);
        game.selectedPiece = pieceId;
        if (paths.size() > 1) { game.turnPhase = TurnPhase.WAITING_PATH_SELECTION; return null; }
        return move(room, player, paths.getFirst());
    }
    public MoveResult selectPath(RoomRuntime room, long player, String pathId) {
        var game = requireTurn(room, player);
        INVALID_TURN_PHASE.require(game.turnPhase == TurnPhase.WAITING_PATH_SELECTION);
        PATH_NOT_ELIGIBLE.require(pathId != null && YutBoard.paths(game.pieces.get(game.selectedPiece)).contains(pathId));
        return move(room, player, pathId);
    }
    public record MoveResult(List<String> pieceIds, String fromNodeId, String toNodeId, boolean finished,
                             List<String> stackedPieceIds, List<String> capturedPieceIds, boolean bonusThrowGranted) {}
    private MoveResult move(RoomRuntime room, long player, String path) {
        var game = game(room);
        var piece = game.pieces.get(game.selectedPiece);
        var token = game.tokens.get(game.selectedToken);
        var destination = YutBoard.move(piece, token.steps(), path);
        String from = piece.nodeId;
        List<String> moving = List.copyOf(piece.group);
        List<String> captured = new ArrayList<>();
        List<String> stacked = new ArrayList<>();
        if (!destination.finished()) for (var other : game.pieces.values()) {
            if (moving.contains(other.id) || other.status != PieceStatus.ON_BOARD || !destination.nodeId().equals(other.nodeId)) continue;
            if (other.ownerId.equals(piece.ownerId)) stacked.add(other.id); else captured.add(other.id);
        }
        for (String id : captured) {
            var other = game.pieces.get(id);
            other.status = PieceStatus.READY; other.nodeId = null; other.route = YutBoard.OUTER;
            other.history = new ArrayList<>(); other.group = List.of(id);
        }
        var group = new ArrayList<>(moving);
        group.addAll(stacked);
        // Stable representative, independent of which member was moved onto the stack.
        var members = game.pieces.keySet().stream().filter(group::contains).toList();
        for (String id : members) {
            var member = game.pieces.get(id);
            member.status = destination.finished() ? PieceStatus.FINISHED : PieceStatus.ON_BOARD;
            member.nodeId = destination.nodeId(); member.route = destination.route();
            member.history = new ArrayList<>(destination.history()); member.group = members;
        }
        game.tokens.remove(token.moveTokenId()); game.usedTokens.add(token.moveTokenId());
        game.selectedToken = null; game.selectedPiece = null;
        if (!captured.isEmpty()) game.pendingBonusThrows++;
        var result = new MoveResult(moving, from, destination.nodeId(), destination.finished(), List.copyOf(stacked), List.copyOf(captured), !captured.isEmpty());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("playerId", player); payload.put("pieceIds", result.pieceIds());
        payload.put("fromNodeId", from); payload.put("toNodeId", result.toNodeId()); payload.put("finished", result.finished());
        payload.put("stackedPieceIds", result.stackedPieceIds()); payload.put("capturedPieceIds", result.capturedPieceIds());
        payload.put("bonusThrowGranted", result.bonusThrowGranted());
        boolean won = game.pieces.values().stream().filter(p -> p.ownerId.equals(piece.ownerId)).allMatch(p -> p.status == PieceStatus.FINISHED);
        if (won) {
            game.winnerOwner = piece.ownerId; game.phase = Phase.FINISHED;
            room.session.status = GameSessionRuntime.Status.FINISHED;
        } else if (!game.tokens.isEmpty()) game.turnPhase = TurnPhase.WAITING_MOVE;
        else if (game.pendingBonusThrows > 0) game.turnPhase = TurnPhase.WAITING_THROW;
        else {
            advanceTurn(room);
        }
        events.game(room, "YUT_PIECE_MOVED", payload);
        if (won) events.game(room, "GAME_FINISHED", Map.of());
        else if (game.currentPlayer() != player) turnEvent(room);
        return result;
    }
    private void consumeSelectedToken(RoomRuntime room) {
        var game = game(room);
        var token = game.tokens.remove(game.selectedToken);
        game.usedTokens.add(token.moveTokenId());
        game.selectedToken = null;
        if (!game.tokens.isEmpty()) game.turnPhase = TurnPhase.WAITING_MOVE;
        else if (game.pendingBonusThrows > 0) game.turnPhase = TurnPhase.WAITING_THROW;
        else {
            advanceTurn(room);
            turnEvent(room);
        }
    }
    private void advanceTurn(RoomRuntime room) {
        var game = game(room);
        game.turnIndex = (game.turnIndex + 1) % game.turnOrder.size();
        game.turnNo++;
        game.throwResults.clear();
        game.turnPhase = TurnPhase.WAITING_THROW;
    }
    private void turnEvent(RoomRuntime room) {
        var game = game(room);
        events.game(room, "YUT_TURN_CHANGED", Map.of("turnNo", game.turnNo, "currentPlayerId", game.currentPlayer()));
    }
    public void cancel(RoomRuntime room, String reason) {
        GAME_SESSION_ALREADY_FINISHED.require(!room.session.ended());
        var game = game(room); game.phase = Phase.CANCELLED; game.cancelReason = reason;
        room.session.status = GameSessionRuntime.Status.CANCELLED;
        events.game(room, "GAME_CANCELLED", Map.of("reason", reason));
    }
}
