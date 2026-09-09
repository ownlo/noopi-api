package com.noopi.game.liar;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import com.noopi.content.LiarContent.Keyword;
import com.noopi.game.session.GameRuntime;
import java.util.*;

@JsonIgnoreType
public final class LiarGameRuntime implements GameRuntime {
    public enum Phase { READY, ROLE_REVEAL, DISCUSSION, VOTING, VOTE_RESULT, REVOTING, LIAR_REVEAL, LIAR_GUESS, FINISHED, CANCELLED }
    final String categoryCode;
    final String categoryName;
    Phase phase = Phase.READY;
    Keyword keyword;
    long liarId;
    final Set<Long> checked = new HashSet<>();
    Long firstSpeaker;
    long voteRound;
    Set<Long> candidates = new LinkedHashSet<>();
    // Never serialize. Discarded after aggregation or cancellation.
    final Map<Long, Long> votes = new HashMap<>();
    VoteResult previousResult;
    Long accused;
    String guess;
    Boolean correct;
    String winner;

    public record Count(long playerId, String nickname, int voteCount) {}
    public record VoteResult(long round, boolean tied, List<Count> counts, Long accusedPlayerId) {}
    public LiarGameRuntime(String categoryCode, String categoryName) {
        this.categoryCode = categoryCode;
        this.categoryName = categoryName;
    }
    boolean voting() { return phase == Phase.VOTING || phase == Phase.REVOTING; }
}
