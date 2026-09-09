package com.noopi;

import com.fasterxml.jackson.databind.*;
import com.noopi.application.GameApplication;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class HttpWebSocketIntegrationTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired GameApplication app;
    @Autowired com.noopi.room.RoomStore rooms;
    HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    List<WebSocket> sockets = new ArrayList<>();
    String base() { return "http://localhost:" + port; }
    String client() { return UUID.randomUUID().toString(); }

    HttpResponse<String> request(String method, String path, String client, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base() + path)).timeout(Duration.ofSeconds(10));
        if (client != null) builder.header("X-Client-Id", client);
        if (body != null) builder.header("Content-Type", "application/json");
        return http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    JsonNode body(HttpResponse<String> response, int status) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        return json.readTree(response.body());
    }
    String playerBody(String nickname) { return "{\"nickname\":\"" + nickname + "\",\"gender\":\"MALE\"}"; }
    @AfterEach void closeSockets() { sockets.forEach(s -> s.sendClose(WebSocket.NORMAL_CLOSURE, "test done")); }

    @Test void restContractsCatalogMigrationAndErrors() throws Exception {
        var categories = body(request("GET", "/api/games/liar/categories", null, null), 200).get("categories");
        assertThat(categories.get(0).get("code").asText()).isEqualTo("RANDOM");
        assertThat(categories.get(0).get("virtual").asBoolean()).isTrue();
        assertThat(categories.size()).isEqualTo(4);
        assertThat(body(request("GET", "/api/games", null, null), 200).at("/games/0/maxPlayers").asInt()).isEqualTo(12);
        String host = client();
        var created = body(request("POST", "/api/rooms", host, playerBody(" 방장 ")), 201);
        long room = created.at("/room/roomId").asLong();
        assertThat(created.at("/me/nickname").asText()).isEqualTo("방장");
        assertThat(body(request("GET", "/api/rooms/by-code/" + created.at("/room/roomCode").asText(), client(), null), 200).get("roomId").asLong()).isEqualTo(room);
        body(request("POST", "/api/rooms/" + room + "/players", client(), playerBody("시민")), 201);
        assertThat(body(request("POST", "/api/rooms/" + room + "/players", client(), playerBody("시민")), 409).get("code").asText()).isEqualTo("NICKNAME_ALREADY_EXISTS");
        assertThat(body(request("GET", "/api/rooms/" + room + "/state", client(), null), 403).get("code").asText()).isEqualTo("PLAYER_NOT_IN_ROOM");
        assertThat(body(request("POST", "/api/rooms", host, "{\"nickname\":\"가\",\"gender\":\"X\"}"), 400).get("code").asText()).isEqualTo("INVALID_GENDER");
        assertThat(body(request("POST", "/api/rooms", host, "{\"gender\":\"MALE\"}"), 400).get("code").asText()).isEqualTo("INVALID_NICKNAME");
        assertThat(request("GET", "/api/rooms/" + room + "/state", null, null).statusCode()).isEqualTo(403);
        assertThat(request("GET", "/api/games/liar/keywords", null, null).statusCode()).isEqualTo(404);
        assertThat(body(request("GET", "/actuator/health", null, null), 200).get("status").asText()).isEqualTo("UP");
        assertThat(body(request("GET", "/v3/api-docs", null, null), 200).get("paths").size()).isEqualTo(15);
    }

    @Test void fullHttpGameFlowAndBroadcastPrivacy() throws Exception {
        String host = client();
        var created = body(request("POST", "/api/rooms", host, playerBody("방장")), 201);
        long room = created.at("/room/roomId").asLong();
        Map<Long, String> players = new LinkedHashMap<>();
        players.put(created.at("/me/playerId").asLong(), host);
        for (int i = 1; i < 4; i++) {
            String client = client();
            var joined = body(request("POST", "/api/rooms/" + room + "/players", client, playerBody("시민" + i)), 201);
            players.put(joined.get("playerId").asLong(), client);
        }
        var listener = new Listener(); connect(room, host, listener);
        String path = "/api/rooms/" + room;
        long session = body(request("POST", path + "/game-sessions", host, "{\"gameType\":\"LIAR\",\"config\":{\"categoryCode\":\"PLACE\"}}"), 201).get("gameSessionId").asLong();
        String game = path + "/game-sessions/" + session;
        for (String client : players.values()) {
            var ready = body(request("GET", path + "/state", client, null), 200)
                .at("/gameSession/gameState");
            assertThat(ready.get("phase").asText()).isEqualTo("READY");
            assertThat(ready.get("categoryCode").asText()).isEqualTo("PLACE");
            assertThat(ready.get("categoryName").asText()).isEqualTo("장소");
            assertThat(ready.has("myRole")).isFalse();
            assertThat(ready.has("keyword")).isFalse();
        }
        assertThat(request("POST", game + "/start", host, null).statusCode()).isEqualTo(204);
        long liar = 0; String keyword = null;
        for (var p : players.entrySet()) {
            var state = body(request("GET", path + "/state", p.getValue(), null), 200).at("/gameSession/gameState");
            if (state.get("myRole").asText().equals("LIAR")) { liar = p.getKey(); assertThat(state.get("keyword").isNull()).isTrue(); }
            else keyword = state.get("keyword").asText();
            assertThat(request("POST", game + "/liar/role-check", p.getValue(), null).statusCode()).isEqualTo(204);
        }
        assertThat(liar).isPositive(); assertThat(keyword).isNotBlank();
        assertThat(body(request("POST", game + "/liar/votes/start", host, null), 200).get("voteRound").asInt()).isEqualTo(1);
        final long liarId = liar;
        long citizen = players.keySet().stream().filter(id -> id != liarId).findFirst().orElseThrow();
        for (var p : players.entrySet()) {
            long target = p.getKey() == liar ? citizen : liar;
            assertThat(request("POST", game + "/liar/votes", p.getValue(), "{\"voteRound\":1,\"targetPlayerId\":" + target + "}").statusCode()).isEqualTo(204);
        }
        for (var p : players.entrySet()) {
            var guessState = body(request("GET", path + "/state", p.getValue(), null), 200)
                .at("/gameSession/gameState");
            assertThat(guessState.get("phase").asText()).isEqualTo("LIAR_GUESS");
            assertThat(guessState.at("/liarPlayer/playerId").asLong()).isEqualTo(liar);
            assertThat(guessState.at("/liarPlayer/nickname").asText()).isNotBlank();
        }
        assertThat(body(request("POST", game + "/liar/guess", players.get(citizen), "{\"answer\":\"테스트\"}"), 403).get("code").asText()).isEqualTo("NOT_LIAR");
        assertThat(body(request("POST", game + "/liar/guess", players.get(liar), json.writeValueAsString(Map.of("answer", keyword))), 200).get("correct").asBoolean()).isTrue();
        var finished = body(request("GET", path + "/state", players.get(liar), null), 200);
        assertThat(finished.at("/gameSession/gameState/result/keyword").asText()).isEqualTo(keyword);
        assertThat(finished.toString()).doesNotContain("targetPlayerId", "voterPlayerId", "acceptedAnswers");
        listener.awaitType("GAME_FINISHED");
        assertThat(String.join("\n", listener.received)).doesNotContain("targetPlayerId", "voterPlayerId", keyword, "myRole");
        for (String message : listener.received) {
            var event = json.readTree(message);
            assertThat(event.get("roomId").asLong()).isEqualTo(room);
            assertThat(event.get("eventId").asText()).isNotBlank();
            assertThat(event.get("occurredAt").asText()).isNotBlank();
        }
    }

    @Test void websocketRejectsNonMembersAndOtherRooms() throws Exception {
        String host = client(); var room = app.create(host, "방장", "MALE");
        String stranger = client(); app.create(stranger, "다른방", "FEMALE");
        assertThatThrownBy(() -> connect(room.room().roomId(), stranger, new Listener()))
            .hasCauseInstanceOf(WebSocketHandshakeException.class);
        var first = new Listener(); connect(room.room().roomId(), host, first);
        var another = app.create(client(), "분리방", "MALE");
        app.join(another.room().roomId(), client(), "합류", "MALE");
        app.join(room.room().roomId(), client(), "이방만", "MALE");
        first.awaitType("PLAYER_JOINED");
        assertThat(String.join("", first.received)).contains("이방만").doesNotContain("합류");
    }

    @Test void multipleSocketsDisconnectOnlyOnLastAndReconnectRestoresSamePlayer() throws Exception {
        String host = client(); var room = app.create(host, "방장", "MALE"); long id = room.room().roomId();
        var watcher = new Listener(); connect(id, host, watcher);
        String guest = client(); var p = app.join(id, guest, "손님", "FEMALE");
        var one = connect(id, guest, new Listener()); var two = connect(id, guest, new Listener());
        one.sendClose(WebSocket.NORMAL_CLOSURE, "close one").get(5, TimeUnit.SECONDS);
        // A subsequent HTTP read still sees the second connection.
        assertThat(app.state(id, guest).me().connectionStatus()).isEqualTo("CONNECTED");
        two.sendClose(WebSocket.NORMAL_CLOSURE, "close two").get(5, TimeUnit.SECONDS);
        watcher.awaitType("PLAYER_DISCONNECTED");
        assertThat(app.state(id, guest).me().connectionStatus()).isEqualTo("DISCONNECTED");
        connect(id, guest, new Listener()); watcher.awaitType("PLAYER_RECONNECTED");
        assertThat(app.state(id, guest).me().playerId()).isEqualTo(p.playerId());
        assertThat(app.state(id, guest).me().connectionStatus()).isEqualTo("CONNECTED");
        app.leave(id, guest);
        assertThatThrownBy(() -> connect(id, guest, new Listener())).hasCauseInstanceOf(WebSocketHandshakeException.class);
    }
    WebSocket connect(long room, String client, Listener listener) throws Exception {
        int count = rooms.inRoom(room, r -> r.players.values().stream().filter(p -> p.clientId.equals(client))
            .findFirst().map(p -> p.connections.size()).orElse(0));
        var socket = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
            .buildAsync(URI.create("ws://localhost:" + port + "/ws?roomId=" + room + "&clientId=" + client), listener).get(10, TimeUnit.SECONDS);
        sockets.add(socket);
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).until(() ->
            rooms.inRoom(room, r -> r.player(client).connections.size()) == count + 1);
        return socket;
    }
    static class Listener implements WebSocket.Listener {
        List<String> received = new CopyOnWriteArrayList<>();
        BlockingQueue<String> pending = new LinkedBlockingQueue<>();
        StringBuilder fragments = new StringBuilder();
        @Override public void onOpen(WebSocket socket) { socket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            fragments.append(data);
            if (last) { String message = fragments.toString(); fragments.setLength(0); received.add(message); pending.add(message); }
            socket.request(1); return CompletableFuture.completedFuture(null);
        }
        void awaitType(String type) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                String next = pending.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                if (next != null && next.contains("\"type\":\"" + type + "\"")) return;
            }
            fail("Missing event " + type + ": " + received);
        }
    }
}
