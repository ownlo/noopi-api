package com.noopi.game.pig;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import com.noopi.game.session.GameRuntime;
import java.util.*;

/** Mutable only under the owning Room monitor; never serialized directly. */
@JsonIgnoreType
public final class PigGameRuntime implements GameRuntime {
    public static final int TARGET_SCORE = 50;
    public static final int INITIAL_BUST_PERCENT = 10;
    public static final int BUST_INCREMENT_PERCENT = 10;
    public static final int MAX_BUST_PERCENT = 90;
    public enum Phase { READY, PLAYING, FINISHED, CANCELLED }
    public enum PlayerStatus { PLAYING, FINISHED }
    public enum TurnOutcome { STOPPED, BUSTED }
    public static final class PlayerState {
        public final long playerId;
        public int totalScore;
        public PlayerStatus status = PlayerStatus.PLAYING;
        public Integer rank;
        public PlayerState(long playerId) { this.playerId = playerId; }
    }
    public Phase phase = Phase.READY;
    public final Map<Long, PlayerState> players = new LinkedHashMap<>();
    public final List<Long> turnOrder = new ArrayList<>();
    public final List<Long> finishOrder = new ArrayList<>();
    public final Set<String> processedActionKeys = new HashSet<>();
    public int turnIndex;
    public int turnScore;
    public int successfulRollCount;
    public Integer lastDiceValue;
    public TurnOutcome lastTurnOutcome;
    public int lostTurnScore;
    public String cancelReason;
    public long currentPlayer() { return turnOrder.get(turnIndex); }
    public PlayerState player(long playerId) { return players.get(playerId); }
    public int bustProbabilityPercent() {
        return Math.min(INITIAL_BUST_PERCENT + successfulRollCount * BUST_INCREMENT_PERCENT, MAX_BUST_PERCENT);
    }
    public double bustProbability() { return bustProbabilityPercent() / 100.0; }
}
