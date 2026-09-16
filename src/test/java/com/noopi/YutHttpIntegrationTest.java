package com.noopi;

import com.fasterxml.jackson.databind.*;
import com.noopi.room.RoomStore;
import com.noopi.game.yut.YutGameRuntime;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class YutHttpIntegrationTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired RoomStore rooms;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    final Map<Long, String> clients = new LinkedHashMap<>();
    long room;
    String host, gamePath;
    HttpResponse<String> request(String method, String path, String client, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(10))
            .header("X-Client-Id", client).header("Content-Type", "application/json")
            .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    JsonNode value(HttpResponse<String> response, int status) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        return response.body().isEmpty() ? json.nullNode() : json.readTree(response.body());
    }
    void setup(int count, String mode) throws Exception {
        host = UUID.randomUUID().toString();
        var created = value(request("POST", "/api/rooms", host, "{\"nickname\":\"방장\",\"gender\":\"MALE\"}"), 201);
        room = created.at("/room/roomId").asLong(); clients.put(created.at("/me/playerId").asLong(), host);
        for (int i = 1; i < count; i++) {
            String client = UUID.randomUUID().toString();
            var player = value(request("POST", "/api/rooms/" + room + "/players", client,
                "{\"nickname\":\"손님" + i + "\",\"gender\":\"FEMALE\"}"), 201);
            clients.put(player.get("playerId").asLong(), client);
        }
        long session = value(request("POST", "/api/rooms/" + room + "/game-sessions", host,
            "{\"gameType\":\"YUT\",\"config\":{\"mode\":\"" + mode + "\"}}"), 201).get("gameSessionId").asLong();
        gamePath = "/api/rooms/" + room + "/game-sessions/" + session;
    }
    JsonNode state(String client) throws Exception { return value(request("GET", "/api/rooms/" + room + "/state", client, null), 200).at("/gameSession/gameState"); }
    String actor() throws Exception { return clients.get(state(host).at("/turn/currentPlayerId").asLong()); }
    void throwUntilMove(String actor) throws Exception {
        for (int i = 0; i < 100 && state(actor).at("/myAction/type").asText().equals("THROW_YUT"); i++) {
            var result = value(request("POST", gamePath + "/yut/throws", actor, null), 200);
            assertThat(result.get("steps").asInt()).isBetween(-1, 5).isNotZero();
            assertThat(result.get("moveTokenId").asText()).isNotBlank();
        }
        assertThat(state(actor).at("/myAction/type").asText()).isEqualTo("SELECT_MOVE_TOKEN");
    }
    String selectFirstToken(String actor) throws Exception {
        String token = state(actor).at("/myAction/moveTokenIds/0").asText();
        value(request("POST", gamePath + "/yut/move-selections", actor, json.writeValueAsString(Map.of("moveTokenId", token))), 204);
        return state(actor).at("/myAction/eligiblePieceIds/0").asText();
    }
    @Test void actualHttpAndSocketFlowSupportsCaptureBonusShortcutsEmpty202AndFinish() throws Exception {
        setup(2, "INDIVIDUAL"); value(request("POST", gamePath + "/start", host, null), 204);
        String actor = actor();
        var listener = new HttpWebSocketIntegrationTest.Listener();
        var socket = http.newWebSocketBuilder().buildAsync(URI.create("ws://localhost:" + port + "/ws?roomId=" + room + "&clientId=" + actor), listener).get(5, TimeUnit.SECONDS);
        try {
            long actorId = state(actor).at("/turn/currentPlayerId").asLong();
            String other = clients.entrySet().stream().filter(e -> e.getKey() != actorId).findFirst().orElseThrow().getValue();
            assertThat(state(other).get("myAction").isNull()).isTrue();
            value(request("POST", gamePath + "/yut/throws", other, null), 403);
            throwUntilMove(actor);
            int steps = state(actor).at("/turn/moveTokens/0/steps").asInt();
            rooms.inRoom(room, r -> {
                var g = (YutGameRuntime) r.session.game;
                g.pieces.values().stream().filter(p -> !p.ownerId.equals(Long.toString(actorId))).limit(2).forEach(p -> {
                    p.status = YutGameRuntime.PieceStatus.ON_BOARD; p.nodeId = "OUTER_" + steps;
                }); return null;
            });
            String piece = selectFirstToken(actor);
            var moved = value(request("POST", gamePath + "/yut/piece-selections", actor, json.writeValueAsString(Map.of("pieceId", piece))), 200);
            assertThat(moved.get("capturedPieceIds").size()).isEqualTo(2);
            assertThat(moved.get("bonusThrowGranted").asBoolean()).isTrue();
            listener.awaitType("YUT_PIECE_MOVED");
            // Stage only test runtime, not a production endpoint, at a branch with one move token.
            rooms.inRoom(room, r -> {
                var g = (YutGameRuntime) r.session.game;
                var p = g.pieces.get(piece); p.nodeId = "OUTER_5";
                g.tokens.clear(); g.tokens.put("route-test", new YutGameRuntime.MoveToken("route-test", YutGameRuntime.Result.GEOL, 3));
                g.turnPhase = YutGameRuntime.TurnPhase.WAITING_MOVE; return null;
            });
            value(request("POST", gamePath + "/yut/move-selections", actor, "{\"moveTokenId\":\"route-test\"}"), 204);
            var waiting = request("POST", gamePath + "/yut/piece-selections", actor, json.writeValueAsString(Map.of("pieceId", piece)));
            value(waiting, 202); assertThat(waiting.body()).isEmpty();
            assertThat(state(actor).at("/myAction/type").asText()).isEqualTo("SELECT_PATH");
            var shortcut = value(request("POST", gamePath + "/yut/path-selections", actor, "{\"pathId\":\"CENTER_SHORTCUT_A\"}"), 200);
            assertThat(shortcut.get("toNodeId").asText()).isEqualTo("CENTER_3");
            rooms.inRoom(room, r -> {
                var g = (YutGameRuntime) r.session.game;
                g.pieces.values().stream().filter(p -> p.ownerId.equals(Long.toString(actorId))).forEach(p -> {
                    p.status = p.id.equals(piece) ? YutGameRuntime.PieceStatus.ON_BOARD : YutGameRuntime.PieceStatus.FINISHED;
                    p.nodeId = p.id.equals(piece) ? "OUTER_20" : null; p.route = "OUTER";
                });
                g.tokens.put("finish-test", new YutGameRuntime.MoveToken("finish-test", YutGameRuntime.Result.DO, 1));
                g.turnPhase = YutGameRuntime.TurnPhase.WAITING_MOVE; return null;
            });
            value(request("POST", gamePath + "/yut/move-selections", actor, "{\"moveTokenId\":\"finish-test\"}"), 204);
            assertThat(value(request("POST", gamePath + "/yut/piece-selections", actor, json.writeValueAsString(Map.of("pieceId", piece))), 200).get("finished").asBoolean()).isTrue();
            assertThat(state(actor).at("/winnerPlayer/playerId").asLong()).isEqualTo(actorId);
            listener.awaitType("GAME_FINISHED");
            assertThat(String.join("\n", listener.received)).doesNotContain("eligiblePieceIds", "eligiblePathIds");
        } finally { socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS); }
    }
    @Test void teamStateStartPermissionsAndCancelFollowFrontendContract() throws Exception {
        setup(4, "TEAM");
        assertThat(state(host).get("phase").asText()).isEqualTo("TEAM_SELECT");
        assertThat(state(host).get("canStart").asBoolean()).isFalse();
        int index = 0;
        for (String client : clients.values()) value(request("PUT", gamePath + "/yut/team", client, "{\"team\":\"" + (index++ < 2 ? "NOOPI" : "DAY") + "\"}"), 204);
        assertThat(state(host).get("canStart").asBoolean()).isTrue();
        String guest = clients.values().stream().filter(c -> !c.equals(host)).findFirst().orElseThrow();
        assertThat(state(guest).get("canStart").asBoolean()).isFalse();
        value(request("POST", gamePath + "/start", guest, null), 403);
        value(request("POST", gamePath + "/start", host, null), 204);
        assertThat(state(host).get("pieces").size()).isEqualTo(8);
        value(request("POST", gamePath + "/cancel", host, null), 204);
        assertThat(state(host).get("phase").asText()).isEqualTo("CANCELLED");
    }
}
