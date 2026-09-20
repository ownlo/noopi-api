package com.noopi.game.tooth;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import com.noopi.game.session.GameRuntime;
import java.util.*;

/** Mutable only under the owning Room monitor; never serialized directly. */
@JsonIgnoreType
public final class ToothGameRuntime implements GameRuntime {
    public static final int TOOTH_COUNT = 24;
    public enum Phase { READY, PLAYING, FINISHED, CANCELLED }
    public enum Outcome { SAFE, BOMB }
    public record Selection(long sequence, long playerId, int toothId, Outcome outcome,
                            Long nextCurrentTurnPlayerId) {}

    public Phase phase = Phase.READY;
    public final List<Long> turnOrder = new ArrayList<>();
    public final Set<Integer> selectedToothIds = new HashSet<>();
    public final Map<String, Selection> processedSelections = new HashMap<>();
    public int bombToothId;
    public int turnIndex;
    public long sequence;
    public Selection lastSelection;
    public Long loserPlayerId;
    public String cancelReason;

    public long currentPlayer() { return turnOrder.get(turnIndex); }
    public int remainingToothCount() { return TOOTH_COUNT - selectedToothIds.size(); }
}
