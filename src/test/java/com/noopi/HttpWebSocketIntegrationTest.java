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
    HttpResponse<String> pigAction(String path, String client, String actionKey) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base() + path)).timeout(Duration.ofSeconds(10))
            .header("X-Client-Id", client).header("Idempotency-Key", actionKey)
            .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> toothAction(String path, String client, String actionKey, int toothId) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base() + path)).timeout(Duration.ofSeconds(10))
            .header("X-Client-Id", client).header("Idempotency-Key", actionKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"toothId\":" + toothId + "}"))
            .build(), HttpResponse.BodyHandlers.ofString());
    }
    JsonNode body(HttpResponse<String> response, int status) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        return json.readTree(response.body());
    }
    String playerBody(String nickname) { return "{\"nickname\":\"" + nickname + "\",\"gender\":\"MALE\"}"; }
    @AfterEach void closeSockets() { sockets.forEach(s -> s.sendClose(WebSocket.NORMAL_CLOSURE, "test done")); }

    @Test void corsPreflightAllowsGameActionHeadersAndMethods() throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create(base() + "/api/rooms/1/game-sessions/1/yut/team"))
            .header("Origin", "http://localhost:3000")
            .header("Access-Control-Request-Method", "PUT")
            .header("Access-Control-Request-Headers", "content-type,x-client-id,idempotency-key")
            .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
            .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).contains("http://localhost:3000");
        assertThat(response.headers().firstValue("Access-Control-Allow-Methods")).hasValueSatisfying(methods ->
            assertThat(methods).contains("PUT"));
        assertThat(response.headers().firstValue("Access-Control-Allow-Headers")).hasValueSatisfying(headers ->
            assertThat(headers.toLowerCase(Locale.ROOT)).contains("idempotency-key"));
    }

    @Test void restContractsCatalogMigrationAndErrors() throws Exception {
        var categories = body(request("GET", "/api/games/liar/categories", null, null), 200).get("categories");
        assertThat(categories.get(0).get("code").asText()).isEqualTo("RANDOM");
        assertThat(categories.get(0).get("virtual").asBoolean()).isTrue();
        assertThat(categories.size()).isEqualTo(4);
        var catalog = body(request("GET", "/api/games", null, null), 200);
        var catalogCategories = catalog.get("catalogCategories");
        assertThat(catalogCategories).hasSize(7);
        assertThat(catalogCategories.get(0).get("code").asText()).isEqualTo("MINI_GAME");
        assertThat(catalogCategories.get(0).get("name").asText()).isEqualTo("미니게임");
        assertThat(catalogCategories.get(0).get("order").asInt()).isEqualTo(1);
        assertThat(catalogCategories.get(1).get("code").asText()).isEqualTo("PARTY_GAME");
        assertThat(catalogCategories.get(2).get("code").asText()).isEqualTo("DEDUCTION");
        assertThat(catalogCategories.get(3).get("code").asText()).isEqualTo("STRATEGY");
        assertThat(catalogCategories.get(4).get("code").asText()).isEqualTo("LUCK");
        assertThat(catalogCategories.get(5).get("code").asText()).isEqualTo("INDIVIDUAL");
        assertThat(catalogCategories.get(6).get("code").asText()).isEqualTo("TEAM");
        Set<String> catalogCodes = new HashSet<>();
        int previousOrder = Integer.MIN_VALUE;
        for (var category : catalogCategories) {
            assertThat(catalogCodes.add(category.get("code").asText())).isTrue();
            assertThat(category.get("order").asInt()).isGreaterThanOrEqualTo(previousOrder);
            previousOrder = category.get("order").asInt();
        }
        var games = catalog.get("games");
        for (var game : games) {
            if (!game.get("enabled").asBoolean()) continue;
            var categoryCodes = game.get("catalogCategoryCodes");
            assertThat(categoryCodes.isEmpty()).isFalse();
            Set<String> uniqueCodes = new HashSet<>();
            for (var categoryCode : categoryCodes) {
                assertThat(catalogCodes).contains(categoryCode.asText());
                assertThat(uniqueCodes.add(categoryCode.asText())).isTrue();
            }
        }
        assertThat(games.get(0).get("maxPlayers").asInt()).isEqualTo(12);
        assertThat(games.get(0).get("catalogCategoryCodes").get(0).asText()).isEqualTo("PARTY_GAME");
        assertThat(games.get(1).get("gameType").asText()).isEqualTo("BLIND");
        assertThat(games.get(1).get("catalogCategoryCodes").toString()).doesNotContain("MINI_GAME");
        assertThat(games.get(1).get("minPlayers").asInt()).isEqualTo(2);
        assertThat(games.get(1).get("maxPlayers").asInt()).isEqualTo(2);
        assertThat(games.get(2).get("gameType").asText()).isEqualTo("MAFIA");
        assertThat(games.get(2).get("minPlayers").asInt()).isEqualTo(4);
        assertThat(games.get(2).get("maxPlayers").asInt()).isEqualTo(12);
        assertThat(games.get(4).get("gameType").asText()).isEqualTo("PIG");
        assertThat(games.get(4).get("catalogCategoryCodes")).hasSize(3);
        assertThat(games.get(4).get("catalogCategoryCodes").get(0).asText()).isEqualTo("MINI_GAME");
        assertThat(games.get(4).get("catalogCategoryCodes").get(1).asText()).isEqualTo("LUCK");
        assertThat(games.get(4).get("catalogCategoryCodes").get(2).asText()).isEqualTo("INDIVIDUAL");
        assertThat(games.get(4).get("minPlayers").asInt()).isEqualTo(2);
        assertThat(games.get(4).get("maxPlayers").asInt()).isEqualTo(6);
        assertThat(games.get(5).get("gameType").asText()).isEqualTo("TOOTH");
        assertThat(games.get(5).get("minPlayers").asInt()).isEqualTo(2);
        assertThat(games.get(5).get("maxPlayers").asInt()).isEqualTo(8);
        assertThat(games.get(5).get("catalogCategoryCodes").get(0).asText()).isEqualTo("MINI_GAME");
        String host = client();
        var created = body(request("POST", "/api/rooms", host, playerBody(" 방장 ")), 201);
        long room = created.at("/room/roomId").asLong();
        assertThat(created.at("/room/roomCode").asText()).matches("\\d{6}");
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
        assertThat(body(request("GET", "/v3/api-docs", null, null), 200).get("paths").size()).isEqualTo(33);
    }

    @Test void hostCanKickLobbyGuestThroughHttpAndGuestCanRejoin() throws Exception {
        String host = client();
        var created = body(request("POST", "/api/rooms", host, playerBody("방장")), 201);
        long room = created.at("/room/roomId").asLong();
        long hostId = created.at("/me/playerId").asLong();
        String guest = client();
        long guestId = body(request("POST", "/api/rooms/" + room + "/players", guest, playerBody("손님")), 201)
            .get("playerId").asLong();
        String kickPath = "/api/rooms/" + room + "/players/";

        assertThat(body(request("DELETE", kickPath + hostId, guest, null), 403).get("code").asText())
            .isEqualTo("NOT_ROOM_HOST");
        assertThat(body(request("DELETE", kickPath + hostId, host, null), 422).get("code").asText())
            .isEqualTo("ROOM_HOST_CANNOT_BE_KICKED");
        assertThat(request("DELETE", kickPath + guestId, host, null).statusCode()).isEqualTo(204);
        assertThat(body(request("GET", "/api/rooms/" + room + "/state", guest, null), 403).get("code").asText())
            .isEqualTo("PLAYER_NOT_IN_ROOM");

        long rejoinedId = body(request("POST", "/api/rooms/" + room + "/players", guest, playerBody("손님")), 201)
            .get("playerId").asLong();
        assertThat(rejoinedId).isNotEqualTo(guestId);
        body(request("POST", "/api/rooms/" + room + "/game-sessions", host,
            "{\"gameType\":\"PIG\",\"config\":{}}"), 201);
        assertThat(body(request("DELETE", kickPath + rejoinedId, host, null), 409).get("code").asText())
            .isEqualTo("ACTIVE_GAME_SESSION_EXISTS");
    }

    @Test void pigHttpFlowUsesServerStateEventsAndIdempotency() throws Exception {
        String host = client();
        var created = body(request("POST", "/api/rooms", host, playerBody("방장")), 201);
        long room = created.at("/room/roomId").asLong();
        long hostId = created.at("/me/playerId").asLong();
        String guest = client();
        long guestId = body(request("POST", "/api/rooms/" + room + "/players", guest, playerBody("손님")), 201)
            .get("playerId").asLong();
        var listener = new Listener(); connect(room, host, listener);
        String roomPath = "/api/rooms/" + room;
        long session = body(request("POST", roomPath + "/game-sessions", host,
            "{\"gameType\":\"PIG\",\"config\":{}}"), 201).get("gameSessionId").asLong();
        String gamePath = roomPath + "/game-sessions/" + session;
        assertThat(request("POST", gamePath + "/start", host, null).statusCode()).isEqualTo(204);

        var before = body(request("GET", roomPath + "/state", host, null), 200).at("/gameSession/gameState");
        assertThat(before.get("targetScore").asInt()).isEqualTo(50);
        assertThat(before.get("successfulRollCount").asInt()).isZero();
        assertThat(before.get("bustProbability").asDouble()).isEqualTo(0.1);
        assertThat(before.has("availableDiceValues")).isFalse();
        assertThat(before.has("removedDiceValues")).isFalse();
        long current = before.get("currentPlayerId").asLong();
        String actor = current == hostId ? host : guest;
        assertThat(current).isIn(hostId, guestId);
        assertThat(body(request("POST", gamePath + "/pig/roll", actor, null), 400).get("code").asText())
            .isEqualTo("BAD_REQUEST");
        assertThat(pigAction(gamePath + "/pig/roll", actor, "roll-1").statusCode()).isEqualTo(204);
        listener.awaitType("PIG_ROLL_RESOLVED");
        assertThat(body(pigAction(gamePath + "/pig/roll", actor, "roll-1"), 409).get("code").asText())
            .isEqualTo("DUPLICATE_ACTION");

        var after = body(request("GET", roomPath + "/state", host, null), 200).at("/gameSession/gameState");
        assertThat(after.get("lastDiceValue").asInt()).isBetween(1, 6);
        assertThat(after.get("players").size()).isEqualTo(2);
    }

    @Test void toothHttpFlowHidesBombAndPublishesServerResults() throws Exception {
        String host = client();
        var created = body(request("POST", "/api/rooms", host, playerBody("방장")), 201);
        long room = created.at("/room/roomId").asLong();
        long hostId = created.at("/me/playerId").asLong();
        String guest = client();
        long guestId = body(request("POST", "/api/rooms/" + room + "/players", guest, playerBody("손님")), 201)
            .get("playerId").asLong();
        var listener = new Listener(); connect(room, host, listener);
        String roomPath = "/api/rooms/" + room;
        long session = body(request("POST", roomPath + "/game-sessions", host,
            "{\"gameType\":\"TOOTH\",\"config\":{}}"), 201).get("gameSessionId").asLong();
        String gamePath = roomPath + "/game-sessions/" + session;
        assertThat(request("POST", gamePath + "/start", host, null).statusCode()).isEqualTo(204);

        int selectedTooth = 0;
        String selectedKey = null;
        JsonNode result = null;
        for (int toothId = 1; toothId <= 24; toothId++) {
            var before = body(request("GET", roomPath + "/state", host, null), 200)
                .at("/gameSession/gameState");
            assertThat(before.has("bombToothId")).isFalse();
            assertThat(before.toString()).doesNotContain("BOMB");
            long current = before.get("currentTurnPlayerId").asLong();
            String actor = current == hostId ? host : guest;
            assertThat(current).isIn(hostId, guestId);
            selectedKey = "tooth-" + toothId;
            result = body(toothAction(gamePath + "/tooth/selections", actor, selectedKey, toothId), 200);
            selectedTooth = toothId;
            listener.awaitType("TOOTH_SELECTED");
            if ("BOMB".equals(result.get("outcome").asText())) break;
            assertThat(result.get("nextCurrentTurnPlayerId").isNull()).isFalse();
        }

        assertThat(result).isNotNull();
        assertThat(result.get("outcome").asText()).isEqualTo("BOMB");
        assertThat(result.get("nextCurrentTurnPlayerId").isNull()).isTrue();
        listener.awaitType("GAME_FINISHED");
        var finished = body(request("GET", roomPath + "/state", host, null), 200)
            .at("/gameSession/gameState");
        assertThat(finished.get("phase").asText()).isEqualTo("FINISHED");
        assertThat(finished.at("/result/bombToothId").asInt()).isEqualTo(selectedTooth);
        assertThat(finished.at("/result/loserPlayer/playerId").asLong()).isEqualTo(result.get("playerId").asLong());
        assertThat(finished.has("rankings")).isFalse();
        assertThat(finished.has("winnerPlayer")).isFalse();

        String loserClient = result.get("playerId").asLong() == hostId ? host : guest;
        var retry = body(toothAction(gamePath + "/tooth/selections", loserClient, selectedKey, selectedTooth), 200);
        assertThat(retry).isEqualTo(result);
        assertThat(String.join("\n", listener.received)).doesNotContain("bombToothId");

        long replay = body(request("POST", roomPath + "/game-sessions", host,
            "{\"gameType\":\"TOOTH\",\"config\":{}}"), 201).get("gameSessionId").asLong();
        assertThat(replay).isNotEqualTo(session);
        assertThat(request("POST", roomPath + "/game-sessions/" + replay + "/start", host, null).statusCode()).isEqualTo(204);
        var replayState = body(request("GET", roomPath + "/state", host, null), 200)
            .at("/gameSession/gameState");
        assertThat(replayState.get("remainingToothCount").asInt()).isEqualTo(24);
        assertThat(replayState.get("lastSelection").isNull()).isTrue();
        assertThat(replayState.has("bombToothId")).isFalse();

        assertThat(request("DELETE", roomPath + "/players/me", guest, null).statusCode()).isEqualTo(204);
        var cancelled = body(request("GET", roomPath + "/state", host, null), 200)
            .at("/gameSession/gameState");
        assertThat(cancelled.get("phase").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.get("reason").asText()).isEqualTo("PLAYER_LEFT");
    }

    @Test void hostCanReturnEveryPlayerToLobby() throws Exception {
        String host = client();
        var created = body(request("POST", "/api/rooms", host, playerBody("방장")), 201);
        long room = created.at("/room/roomId").asLong();
        String guest = client();
        body(request("POST", "/api/rooms/" + room + "/players", guest, playerBody("손님")), 201);
        String other = client();
        body(request("POST", "/api/rooms/" + room + "/players", other, playerBody("친구")), 201);
        var listener = new Listener(); connect(room, guest, listener);
        String roomPath = "/api/rooms/" + room;
        long session = body(request("POST", roomPath + "/game-sessions", host,
            "{\"gameType\":\"LIAR\",\"config\":{\"categoryCode\":\"RANDOM\"}}"), 201)
            .get("gameSessionId").asLong();
        assertThat(request("POST", roomPath + "/game-sessions/" + session + "/start", host, null).statusCode()).isEqualTo(204);

        assertThat(body(request("POST", roomPath + "/lobby", guest, null), 403).get("code").asText())
            .isEqualTo("NOT_ROOM_HOST");
        assertThat(request("POST", roomPath + "/lobby", host, null).statusCode()).isEqualTo(204);

        listener.awaitType("ROOM_RETURNED_TO_LOBBY");
        var state = body(request("GET", roomPath + "/state", guest, null), 200);
        assertThat(state.at("/room/status").asText()).isEqualTo("WAITING");
        assertThat(state.get("gameSession").isNull()).isTrue();
        var event = listener.received.stream().map(message -> {
            try { return json.readTree(message); }
            catch (Exception exception) { throw new RuntimeException(exception); }
        }).filter(message -> message.get("type").asText().equals("ROOM_RETURNED_TO_LOBBY")).findFirst().orElseThrow();
        assertThat(event.get("gameSessionId").isNull()).isTrue();
        assertThat(event.get("payload").isEmpty()).isTrue();
    }

    @Test void fullBlindHttpFlowUsesPersonalizedStateAndServerWinner() throws Exception {
        String host = client();
        var created = body(request("POST", "/api/rooms", host, playerBody("방장")), 201);
        long room = created.at("/room/roomId").asLong();
        long hostId = created.at("/me/playerId").asLong();
        String guest = client();
        long guestId = body(request("POST", "/api/rooms/" + room + "/players", guest, playerBody("손님")), 201)
            .get("playerId").asLong();
        var listener = new Listener(); connect(room, host, listener);
        String roomPath = "/api/rooms/" + room;
        long session = body(request("POST", roomPath + "/game-sessions", host,
            "{\"gameType\":\"BLIND\",\"config\":{}}"), 201).get("gameSessionId").asLong();
        String gamePath = roomPath + "/game-sessions/" + session;
        assertThat(request("POST", gamePath + "/start", host, null).statusCode()).isEqualTo(204);

        var hostState = body(request("GET", roomPath + "/state", host, null), 200).at("/gameSession/gameState");
        var guestState = body(request("GET", roomPath + "/state", guest, null), 200).at("/gameSession/gameState");
        String hostKeyword = guestState.get("opponentKeyword").asText();
        String guestKeyword = hostState.get("opponentKeyword").asText();
        assertThat(hostKeyword).isNotEqualTo(guestKeyword);
        assertThat(hostState.at("/opponentPlayer/playerId").asLong()).isEqualTo(guestId);
        assertThat(guestState.at("/opponentPlayer/playerId").asLong()).isEqualTo(hostId);
        assertThat(hostState.toString()).doesNotContain(hostKeyword, "myKeyword", "keywordAssignments");
        assertThat(guestState.toString()).doesNotContain(guestKeyword, "myKeyword", "keywordAssignments");

        assertThat(body(request("POST", gamePath + "/blind/guesses", host, "{\"answer\":\"오답\"}"), 200)
            .get("correct").asBoolean()).isFalse();
        assertThat(body(request("POST", gamePath + "/blind/guesses", host,
            json.writeValueAsString(Map.of("answer", hostKeyword))), 200).get("correct").asBoolean()).isTrue();
        assertThat(body(request("POST", gamePath + "/blind/guesses", guest,
            json.writeValueAsString(Map.of("answer", guestKeyword))), 409).get("code").asText())
            .isEqualTo("GAME_SESSION_ALREADY_FINISHED");
        var result = body(request("GET", roomPath + "/state", guest, null), 200)
            .at("/gameSession/gameState/result");
        assertThat(result.at("/winnerPlayer/playerId").asLong()).isEqualTo(hostId);
        assertThat(result.get("keywordAssignments").size()).isEqualTo(2);
        listener.awaitType("GAME_FINISHED");
        assertThat(String.join("\n", listener.received)).doesNotContain(hostKeyword, guestKeyword);
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
        listener.awaitType("DISCUSSION_STARTED");
        var discussionState = body(request("GET", path + "/state", host, null), 200)
            .at("/gameSession/gameState");
        List<Long> speakingOrder = new ArrayList<>();
        discussionState.get("speakingOrderPlayerIds").forEach(node -> speakingOrder.add(node.asLong()));
        assertThat(speakingOrder).containsExactlyInAnyOrderElementsOf(players.keySet()).doesNotHaveDuplicates();
        assertThat(discussionState.has("firstSpeakerPlayerId")).isFalse();
        assertThat(String.join("\n", listener.received))
            .contains("speakingOrderPlayerIds").doesNotContain("firstSpeakerPlayerId");
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

    @Test void disconnectedHostDoesNotBlockRoomLookupOrNewPlayerJoin() throws Exception {
        String host = client();
        var created = body(request("POST", "/api/rooms", host, playerBody("방장")), 201);
        long room = created.at("/room/roomId").asLong();
        String roomCode = created.at("/room/roomCode").asText();
        var hostSocket = connect(room, host, new Listener());

        hostSocket.sendClose(WebSocket.NORMAL_CLOSURE, "host disconnected").get(5, TimeUnit.SECONDS);
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
            assertThat(app.state(room, host).me().connectionStatus()).isEqualTo("DISCONNECTED"));

        var lookup = body(request("GET", "/api/rooms/by-code/" + roomCode, client(), null), 200);
        assertThat(lookup.get("roomId").asLong()).isEqualTo(room);
        assertThat(lookup.get("joinable").asBoolean()).isTrue();

        String guest = client();
        var joined = body(request("POST", "/api/rooms/" + room + "/players", guest, playerBody("손님")), 201);
        assertThat(joined.get("nickname").asText()).isEqualTo("손님");
        assertThat(app.state(room, guest).me().connectionStatus()).isEqualTo("CONNECTED");
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
