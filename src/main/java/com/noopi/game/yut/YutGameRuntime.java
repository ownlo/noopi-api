package com.noopi.game.yut;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import com.noopi.game.session.GameRuntime;
import java.util.*;

/** Mutable only under the owning Room monitor; never serialized directly. */
@JsonIgnoreType
public final class YutGameRuntime implements GameRuntime {
    public enum Mode { INDIVIDUAL, TEAM }
    public enum Team { NOOPI, DAY }
    public enum Phase { READY, TEAM_SELECT, PLAYING, FINISHED, CANCELLED }
    public enum TurnPhase { WAITING_THROW, WAITING_MOVE, WAITING_PATH_SELECTION }
    public enum PieceStatus { READY, ON_BOARD, FINISHED }
    public enum Result {
        DO(1), GAE(2), GEOL(3), YUT(4), MO(5);
        public final int steps;
        Result(int steps) { this.steps = steps; }
        public boolean bonus() { return this == YUT || this == MO; }
    }
    public record MoveToken(String moveTokenId, Result result, int steps) {}
    public static final class Piece {
        public final String id;
        public final String ownerId;
        public PieceStatus status = PieceStatus.READY;
        public String nodeId;
        public String route = YutBoard.OUTER;
        public List<String> group;
        public Piece(String id, String ownerId) {
            this.id = id; this.ownerId = ownerId; this.group = List.of(id);
        }
    }
    public final Mode mode;
    public Phase phase;
    public final Map<Long, Team> teams = new LinkedHashMap<>();
    public final Map<String, Piece> pieces = new LinkedHashMap<>();
    public final List<Long> turnOrder = new ArrayList<>();
    public int turnIndex;
    public long turnNo = 1;
    public TurnPhase turnPhase = TurnPhase.WAITING_THROW;
    public final List<Result> throwResults = new ArrayList<>();
    public final Map<String, MoveToken> tokens = new LinkedHashMap<>();
    public final Set<String> usedTokens = new HashSet<>();
    public int pendingBonusThrows;
    public long nextToken;
    public String selectedToken;
    public String selectedPiece;
    public String winnerOwner;
    public String cancelReason;

    public YutGameRuntime(Mode mode) {
        this.mode = mode;
        this.phase = mode == Mode.TEAM ? Phase.TEAM_SELECT : Phase.READY;
    }
    public long currentPlayer() { return turnOrder.get(turnIndex); }
    public String owner(long player) {
        return mode == Mode.TEAM ? teams.get(player).name() : Long.toString(player);
    }
}
