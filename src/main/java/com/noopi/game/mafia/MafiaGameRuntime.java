package com.noopi.game.mafia;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import com.noopi.game.session.GameRuntime;
import java.util.*;

@JsonIgnoreType
public final class MafiaGameRuntime implements GameRuntime {
    public enum Phase { READY, ROLE_REVEAL, FIRST_NIGHT, DAY, VOTING, REVOTING, VOTE_RESULT,
        JUDGMENT, JUDGMENT_RESULT, EXECUTION, NIGHT, NIGHT_RESULT, FINISHED, CANCELLED }
    public enum Role { MAFIA, POLICE, DOCTOR, CITIZEN }
    public enum ActionType { ATTACK, INVESTIGATE, HEAL, SUSPECT, CONFIRM }
    public enum JudgmentChoice { EXECUTE, SAVE }
    public record NightAction(ActionType type, Long targetId) {}
    public record Investigation(int nightNo, long targetPlayerId, String targetNickname, boolean mafia) {}
    public record Count(long playerId, String nickname, int voteCount) {}
    public record VoteResult(long round, boolean tied, List<Count> counts, Long executionTargetPlayerId) {}
    public record Death(long playerId, String nickname, Role revealedRole) {}
    public record NightResult(int nightNo, Death deadPlayer, Map<Long, Integer> suspicionCounts) {}

    Phase phase = Phase.READY;
    final Map<Long, Role> roles = new LinkedHashMap<>();
    final Set<Long> alive = new LinkedHashSet<>();
    final Map<Long, Role> revealedRoles = new HashMap<>();
    final Set<Long> checked = new HashSet<>();
    int nightNo;
    int dayNo;
    final Map<Long, NightAction> nightActions = new HashMap<>();
    final Map<Long, List<Investigation>> investigations = new HashMap<>();
    Long previousHealTarget;
    NightResult lastNightResult;
    long voteRound;
    Set<Long> candidates = new LinkedHashSet<>();
    final Map<Long, Long> votes = new HashMap<>();
    VoteResult previousVoteResult;
    Long accused;
    final Map<Long, JudgmentChoice> judgmentVotes = new HashMap<>();
    boolean executed;
    Death executionResult;
    String winnerTeam;

    boolean voting() { return phase == Phase.VOTING || phase == Phase.REVOTING; }
}
