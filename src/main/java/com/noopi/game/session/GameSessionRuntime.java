package com.noopi.game.session;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import java.util.*;
import static com.noopi.api.ErrorCode.*;

@JsonIgnoreType
public final class GameSessionRuntime {
    public enum Status { READY, PLAYING, FINISHED, CANCELLED }
    public record Participant(long playerId, String nickname) {}
    public final long id;
    public final String gameType;
    public Status status = Status.READY;
    public GameRuntime game;
    private Map<Long, Participant> participants = Map.of();
    private final Set<Long> excluded = new HashSet<>();

    public GameSessionRuntime(long id, String gameType, GameRuntime game) {
        this.id = id; this.gameType = gameType; this.game = game;
    }
    public void start(Collection<Participant> players) {
        GAME_SESSION_NOT_READY.require(status == Status.READY);
        Map<Long, Participant> snapshot = new LinkedHashMap<>();
        players.forEach(p -> snapshot.put(p.playerId(), p));
        participants = Collections.unmodifiableMap(snapshot);
        status = Status.PLAYING;
    }
    public Map<Long, Participant> participants() { return participants; }
    public boolean active(long id) { return participants.containsKey(id) && !excluded.contains(id); }
    public List<Long> activeIds() { return participants.keySet().stream().filter(this::active).toList(); }
    public void requireParticipant(long id) { PLAYER_NOT_IN_GAME.require(active(id)); }
    public void exclude(long id) { requireParticipant(id); excluded.add(id); }
    public boolean ended() { return status == Status.FINISHED || status == Status.CANCELLED; }
}
