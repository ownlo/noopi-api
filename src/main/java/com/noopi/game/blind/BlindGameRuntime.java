package com.noopi.game.blind;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import com.noopi.content.BlindContent.Keyword;
import com.noopi.game.session.GameRuntime;
import java.util.LinkedHashMap;
import java.util.Map;

@JsonIgnoreType
public final class BlindGameRuntime implements GameRuntime {
    public enum Phase { READY, GUESSING, FINISHED, CANCELLED }
    Phase phase = Phase.READY;
    final Map<Long, Keyword> assignments = new LinkedHashMap<>();
    final Map<Long, Integer> attemptCounts = new LinkedHashMap<>();
    Long winnerPlayerId;
}
