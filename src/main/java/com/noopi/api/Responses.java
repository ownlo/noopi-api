package com.noopi.api;

import java.util.List;
import java.util.Map;

public final class Responses {
    private Responses() {}
    public record Room(long roomId, String roomCode, String status) {}
    public record RoomState(long roomId, String roomCode, String status, long hostPlayerId) {}
    public record Player(long playerId, String nickname, String gender, boolean host, String connectionStatus) {}
    public record RoomPlayer(long playerId, String nickname, String gender, boolean host,
                             String connectionStatus, boolean currentGameParticipant) {}
    public record CreatedRoom(Room room, Player me) {}
    public record Lookup(long roomId, String roomCode, String status, int playerCount, boolean joinable) {}
    public record Session(long gameSessionId, String gameType, String status) {}
    public record SessionState(long gameSessionId, String gameType, String status, Map<String, Object> gameState) {}
    public record State(RoomState room, Player me, List<RoomPlayer> players, SessionState gameSession) {}
    public record VoteRound(long voteRound) {}
    public record Guess(boolean correct) {}
}
