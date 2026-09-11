package com.noopi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noopi.api.*;
import com.noopi.application.*;
import com.noopi.content.LiarContent;
import com.noopi.game.liar.*;
import com.noopi.game.blind.*;
import com.noopi.realtime.RoomEvents;
import com.noopi.room.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.assertj.core.api.Assertions.*;
import static com.noopi.api.ErrorCode.*;

class GameRulesTest {
    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-09-09T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
    static class RecordingEvents implements RoomEvents {
        record Sent(String type, Map<String, Object> payload) {}
        List<Sent> sent = new CopyOnWriteArrayList<>();
        Set<Long> closed = new HashSet<>();
        public void publish(RoomRuntime r, String type, Long session, Map<String, Object> data) { sent.add(new Sent(type, Map.copyOf(data))); }
        public void closePlayer(long r, long p) {}
        public void closeRoom(long r) { closed.add(r); }
        long count(String type) { return sent.stream().filter(e -> e.type.equals(type)).count(); }
    }
    MutableClock clock;
    RecordingEvents events;
    RoomStore store;
    GameApplication app;
    LiarGameService liarService;
    long room, session;
    List<Long> ids;
    Map<Long, String> clients;
    long liar;
    final ObjectMapper json = new ObjectMapper();

    @BeforeEach void setup() {
        clock = new MutableClock();
        events = new RecordingEvents();
        var random = new Random(7);
        store = new RoomStore(random, clock);
        LiarContent content = new LiarContent() {
            public List<Category> categories() { return List.of(new Category("RANDOM", "랜덤", true)); }
            public void validateCategory(String code) { INVALID_CATEGORY.require("RANDOM".equals(code)); }
            public Keyword choose(String code, Collection<Long> recent) { return new Keyword(1, "바다"); }
        };
        liarService = new LiarGameService(content, random, events, 10);
        app = new GameApplication(store, liarService, new LiarStateProjection(),
            new BlindGameService((count, recent) -> List.of(
                new com.noopi.content.BlindContent.Keyword(1, "바다"),
                new com.noopi.content.BlindContent.Keyword(2, "기린")), events, 10),
            new BlindStateProjection(), events, clock, Duration.ofMinutes(2));
    }
    void createPlayers(int count) {
        var result = app.create("c0", "참가0", "MALE");
        room = result.room().roomId();
        ids = new ArrayList<>(List.of(result.me().playerId()));
        clients = new LinkedHashMap<>();
        clients.put(ids.getFirst(), "c0");
        for (int i = 1; i < count; i++) {
            var p = app.join(room, "c" + i, "참가" + i, "FEMALE");
            ids.add(p.playerId()); clients.put(p.playerId(), "c" + i);
        }
    }

    @Test void disconnectedHostMakesRoomInvisibleToNewPlayers() {
        var created = app.create("host", "방장", "MALE");
        room = created.room().roomId();
        String code = created.room().roomCode();
        long hostId = created.me().playerId();

        disconnect(hostId);

        error(ROOM_NOT_FOUND, () -> app.lookup("guest", code));
        error(ROOM_NOT_FOUND, () -> app.join(room, "guest", "참가자", "FEMALE"));
        assertThat(store.<Integer>inRoom(room, r -> r.players.size())).isEqualTo(1);
    }

    @Test void readyStateRestoresSelectedCategoryForEveryRoomPlayer() {
        createPlayers(3);
        session = app.createSession(room, "c0", "LIAR", "RANDOM").gameSessionId();
        for (long id : ids) {
            var ready = state(id);
            assertThat(ready.get("phase")).isEqualTo("READY");
            assertThat(ready.get("categoryCode")).isEqualTo("RANDOM");
            assertThat(ready.get("categoryName")).isEqualTo("랜덤");
            assertThat(ready).doesNotContainKeys("myRole", "keyword");
        }
    }
    void start(int count) {
        createPlayers(count);
        session = app.createSession(room, "c0", "LIAR", "RANDOM").gameSessionId();
        app.start(room, session, "c0");
        liar = ids.stream().filter(id -> "LIAR".equals(state(id).get("myRole"))).findFirst().orElseThrow();
    }
    Map<String, Object> state(long id) { return app.state(room, clients.get(id)).gameSession().gameState(); }
    void discussion() { for (long id : ids) app.roleCheck(room, session, clients.get(id)); }
    void voting() { discussion(); app.startVote(room, session, "c0"); }
    void cast(long who, long round, long target) { app.vote(room, session, clients.get(who), round, target); }
    void accuse(long target) {
        for (long id : ids) cast(id, 1, id == target ? ids.stream().filter(i -> i != target).findFirst().orElseThrow() : target);
    }
    void error(ErrorCode code, Runnable action) { assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo(code)); }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" ", "123456", " 가나다라마바 "})
    void invalidNicknames(String nickname) { error(INVALID_NICKNAME, () -> app.create("a", nickname, "MALE")); }
    @ParameterizedTest @ValueSource(strings = {"가", "가나다라마", "  누피  ", "😀😀😀😀😀"})
    void validTrimmedNicknames(String nickname) { assertThat(app.create("a", nickname, "FEMALE").me().nickname()).isEqualTo(nickname.strip()); }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"OTHER", "male"})
    void invalidGender(String gender) { error(INVALID_GENDER, () -> app.create("a", "누피", gender)); }
    @Test void duplicateNicknameAndClientRecovery() {
        createPlayers(3);
        error(NICKNAME_ALREADY_EXISTS, () -> app.join(room, "new", " 참가1 ", "MALE"));
        disconnect(ids.get(1));
        var recovered = app.join(room, "c1", "다른값", "MALE");
        assertThat(recovered.playerId()).isEqualTo(ids.get(1));
        assertThat(recovered.nickname()).isEqualTo("참가1");
        assertThat(recovered.gender()).isEqualTo("FEMALE");
        assertThat(recovered.connectionStatus()).isEqualTo("CONNECTED");
        assertThat(app.state(room, "c0").players()).hasSize(3);
    }
    @Test void snapshotTakenAtStartAndLateJoinHasNoSecrets() throws Exception {
        createPlayers(3);
        session = app.createSession(room, "c0", "LIAR", "RANDOM").gameSessionId();
        var before = app.join(room, "before", "시작전", "MALE");
        disconnect(ids.get(2));
        app.start(room, session, "c0");
        assertThat(store.<Boolean>inRoom(room, r -> r.session.active(before.playerId()))).isTrue();
        assertThat(store.<Boolean>inRoom(room, r -> r.session.active(ids.get(2)))).isFalse();
        app.join(room, "late", "늦참", "MALE");
        var late = app.state(room, "late");
        assertThat(late.gameSession().gameState().get("myRole")).isNull();
        assertThat(json.writeValueAsString(late)).doesNotContain("바다", "acceptedAnswers", "clientId");
        error(PLAYER_NOT_IN_GAME, () -> app.roleCheck(room, session, "late"));
    }
    @Test void exactlyOneLiarAndNoOtherRolesInProjection() throws Exception {
        start(12);
        assertThat(ids.stream().filter(i -> "LIAR".equals(state(i).get("myRole")))).hasSize(1);
        for (long id : ids) {
            var snapshot = app.state(room, clients.get(id));
            assertThat(state(id).get("keyword")).isEqualTo(id == liar ? null : "바다");
            String text = json.writeValueAsString(snapshot);
            assertThat(text).doesNotContain("liarId", "roles", "clientId", "acceptedAnswers");
            assertThat(snapshot.players()).allSatisfy(p -> assertThat(p.currentGameParticipant()).isTrue());
        }
        discussion();
        for (long id : ids) {
            assertThat(state(id).get("keyword")).isEqualTo(id == liar ? null : "바다");
            assertThat(ids).contains((Long) state(id).get("firstSpeakerPlayerId"));
        }
    }
    @Test void roleCheckDuplicateAndHostPermissions() {
        start(4);
        error(NOT_ROOM_HOST, () -> app.startVote(room, session, "c1"));
        error(INVALID_GAME_PHASE, () -> app.startVote(room, session, "c0"));
        app.roleCheck(room, session, "c0");
        error(ROLE_ALREADY_CHECKED, () -> app.roleCheck(room, session, "c0"));
        error(GAME_SESSION_NOT_READY, () -> app.start(room, session, "c0"));
        error(ACTIVE_GAME_SESSION_EXISTS, () -> app.createSession(room, "c0", "LIAR", "RANDOM"));
    }
    @Test void rejectsSelfDuplicateStaleAndInvalidTargetWithoutLeakingCounts() throws Exception {
        start(4); voting();
        error(CANNOT_VOTE_SELF, () -> cast(ids.get(0), 1, ids.get(0)));
        error(INVALID_VOTE_ROUND, () -> cast(ids.get(0), 0, ids.get(1)));
        error(INVALID_VOTE_TARGET, () -> cast(ids.get(0), 1, 987654));
        cast(ids.get(0), 1, ids.get(1));
        error(ALREADY_VOTED, () -> cast(ids.get(0), 1, ids.get(2)));
        for (long id : ids) {
            String snapshot = json.writeValueAsString(state(id));
            assertThat(snapshot).doesNotContain("targetPlayerId", "voterPlayerId", "voteCount", "counts", "accusedPlayerId");
        }
        assertThat(json.writeValueAsString(events.sent)).doesNotContain("targetPlayerId", "voterPlayerId", "바다");
    }
    @Test void unlimitedTiesRestrictCandidatesAndNeverRandomlyAccuse() throws Exception {
        start(4); voting();
        for (int round = 1; round <= 100; round++) {
            cast(ids.get(0), round, ids.get(1));
            cast(ids.get(1), round, ids.get(0));
            cast(ids.get(2), round, ids.get(0));
            cast(ids.get(3), round, ids.get(1));
            assertThat(state(ids.get(0)).get("phase")).isEqualTo("REVOTING");
            Map<?, ?> vote = (Map<?, ?>) state(ids.get(2)).get("vote");
            assertThat(vote.get("round")).isEqualTo((long) round + 1);
            assertThat((List<?>) vote.get("eligibleCandidates")).hasSize(2);
        }
        error(INVALID_VOTE_TARGET, () -> cast(ids.get(0), 101, ids.get(3)));
        error(INVALID_VOTE_ROUND, () -> cast(ids.get(0), 100, ids.get(1)));
        assertThat(events.count("REVOTE_STARTED")).isEqualTo(100);
        assertThat(events.count("LIAR_REVEALED")).isZero();
        assertThat(json.writeValueAsString(events.sent)).doesNotContain("targetPlayerId", "voterPlayerId");
    }
    @Test void wrongAccusationFinishesAndRevealsKeywordToEveryone() throws Exception {
        start(4); voting();
        long citizen = ids.stream().filter(i -> i != liar).findFirst().orElseThrow();
        accuse(citizen);
        for (long id : ids) {
            var state = state(id);
            assertThat(state.get("phase")).isEqualTo("FINISHED");
            var result = (Map<?, ?>) state.get("result");
            assertThat(result.get("winner")).isEqualTo("LIAR");
            assertThat(result.get("keyword")).isEqualTo("바다");
            assertThat(result.get("liarGuess")).isNull();
            assertThat(json.writeValueAsString(state)).doesNotContain("targetPlayerId", "voterPlayerId");
        }
        error(INVALID_GAME_PHASE, () -> app.guess(room, session, clients.get(liar), "바다"));
    }
    @ParameterizedTest @CsvSource({"' 바다 ',true", "'바 다',true", "바닷가,false", "SEA,false"})
    void liarGuessIsPrivateRoleRestrictedAllowsOnlySpacingDifferencesAndSingleUse(String answer, boolean correct) {
        start(4); voting(); accuse(liar);
        assertThat(state(liar).get("phase")).isEqualTo("LIAR_GUESS");
        assertThat(state(liar).get("keyword")).isNull();
        long citizen = ids.stream().filter(i -> i != liar).findFirst().orElseThrow();
        for (long id : ids) {
            var liarPlayer = (Map<?, ?>) state(id).get("liarPlayer");
            assertThat(liarPlayer.get("playerId")).isEqualTo(liar);
            assertThat(liarPlayer.get("nickname")).isEqualTo(store.inRoom(room,
                r -> r.session.participants().get(liar).nickname()));
        }
        error(NOT_LIAR, () -> app.guess(room, session, clients.get(citizen), "바다"));
        error(INVALID_ANSWER, () -> app.guess(room, session, clients.get(liar), "  "));
        assertThat(app.guess(room, session, clients.get(liar), answer).correct()).isEqualTo(correct);
        error(GUESS_ALREADY_SUBMITTED, () -> app.guess(room, session, clients.get(liar), "바다"));
        assertThat(((Map<?, ?>) state(liar).get("result")).get("winner")).isEqualTo(correct ? "LIAR" : "CITIZEN");
    }
    @Test void concurrentFinalVotesAggregateOnce() throws Exception {
        start(4); voting();
        runTogether(List.of(() -> cast(ids.get(0), 1, ids.get(1)), () -> cast(ids.get(1), 1, ids.get(0)),
            () -> cast(ids.get(2), 1, ids.get(0)), () -> cast(ids.get(3), 1, ids.get(1))));
        assertThat(events.count("VOTE_COMPLETED")).isEqualTo(1);
        assertThat(events.count("REVOTE_STARTED")).isEqualTo(1);
        assertThat(((Map<?, ?>) state(ids.get(0)).get("vote")).get("round")).isEqualTo(2L);
    }
    @Test void concurrentRoleChecksChooseOneSpeaker() throws Exception {
        start(4);
        runTogether(ids.stream().<Runnable>map(id -> () -> app.roleCheck(room, session, clients.get(id))).toList());
        assertThat(events.count("DISCUSSION_STARTED")).isEqualTo(1);
        assertThat(state(liar).get("phase")).isEqualTo("DISCUSSION");
    }
    @Test void concurrentNicknameJoinAllowsExactlyOne() throws Exception {
        createPlayers(3);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        List<Runnable> joins = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            String client = "join" + i;
            joins.add(() -> {
                try { app.join(room, client, "중복", "MALE"); successes.incrementAndGet(); }
                catch (DomainException ex) { assertThat(ex.code()).isEqualTo(NICKNAME_ALREADY_EXISTS); conflicts.incrementAndGet(); }
            });
        }
        runTogether(joins);
        assertThat(successes.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(11);
    }
    @Test void reconnectPreservesRoleCheckAndSubmittedVote() {
        start(4); voting(); cast(ids.get(1), 1, ids.get(0)); disconnect(ids.get(1));
        app.join(room, "c1", "무시", "MALE");
        assertThat(((Map<?, ?>) state(ids.get(1)).get("vote")).get("myVoteSubmitted")).isEqualTo(true);
        error(ALREADY_VOTED, () -> cast(ids.get(1), 1, ids.get(0)));
        error(ROLE_ALREADY_CHECKED, () -> app.roleCheck(room, session, "c1"));
    }
    @Test void excludeRequiresDisconnectionGraceAndRemovesOnlyOutgoingVote() {
        start(4); voting();
        long target = ids.stream().filter(i -> i != liar && !i.equals(ids.getFirst())).findFirst().orElseThrow();
        long voter = ids.stream().filter(i -> i != target).findFirst().orElseThrow();
        cast(target, 1, voter); cast(voter, 1, target);
        error(PLAYER_NOT_DISCONNECTED, () -> app.exclude(room, session, "c0", target));
        disconnect(target);
        error(PLAYER_NOT_EXCLUDABLE, () -> app.exclude(room, session, "c0", target));
        clock.now = clock.now.plusSeconds(121);
        app.exclude(room, session, "c0", target);
        Map<?, ?> vote = (Map<?, ?>) state(voter).get("vote");
        assertThat(vote.get("requiredVoteCount")).isEqualTo(3);
        assertThat(vote.get("completedVoteCount")).isEqualTo(1);
        assertThat(vote.get("myVoteSubmitted")).isEqualTo(true);
        assertThat((List<?>) vote.get("eligibleCandidates")).anySatisfy(p -> assertThat(((Map<?, ?>) p).get("playerId")).isEqualTo(target));
        error(PLAYER_NOT_IN_GAME, () -> cast(target, 1, voter));
        for (long id : ids) if (id != target && id != voter) cast(id, 1, target);
        assertThat(state(voter).get("phase")).isEqualTo("FINISHED");
        var result = (Map<?, ?>) state(voter).get("result");
        assertThat(((Map<?, ?>) result.get("accusedPlayer")).get("playerId")).isEqualTo(target);
        assertThat(result.get("winner")).isEqualTo("LIAR");
    }
    @Test void excludingLiarCancelsWithoutReplacement() {
        start(4); voting(); disconnect(liar); clock.now = clock.now.plusSeconds(121);
        app.exclude(room, session, "c0", liar);
        assertThat(state(liar).get("phase")).isEqualTo("CANCELLED");
        assertThat(events.count("GAME_CANCELLED")).isEqualTo(1);
        error(GAME_SESSION_ALREADY_FINISHED, () -> app.cancel(room, session, "c0"));
    }
    @Test void explicitHostLeaveClosesRoomWithoutTransfer() {
        start(4);
        if (liar != ids.getFirst()) {
            app.leave(room, clients.get(liar));
            assertThat(app.state(room, "c0").gameSession().status()).isEqualTo("CANCELLED");
        }
        app.leave(room, "c0");
        error(ROOM_NOT_FOUND, () -> app.state(room, "c1"));
        assertThat(events.count("ROOM_CLOSED")).isEqualTo(1);
        assertThat(events.count("HOST_CHANGED")).isZero();
    }
    @Test void freshGameHasNewIdAndNoPreviousActions() {
        start(4); app.cancel(room, session, "c0");
        long old = session;
        session = app.createSession(room, "c0", "LIAR", "RANDOM").gameSessionId();
        assertThat(session).isNotEqualTo(old);
        app.start(room, session, "c0");
        assertThat(state(ids.getFirst()).get("roleCheckedCount")).isEqualTo(0);
        error(GAME_SESSION_NOT_FOUND, () -> app.roleCheck(room, old, "c0"));
    }
    @SuppressWarnings("unchecked")
    @Test void roleCheckStatusesIdentifyEachPlayer() {
        start(4);
        long checkedPlayerId = ids.get(1);
        app.roleCheck(room, session, clients.get(checkedPlayerId));

        var statuses = (List<Map<String, Object>>) state(ids.getFirst()).get("playerRoleCheckStatuses");
        assertThat(statuses).anySatisfy(status -> {
            assertThat(status.get("playerId")).isEqualTo(checkedPlayerId);
            assertThat(status.get("checked")).isEqualTo(true);
        });
        assertThat(statuses).filteredOn(status -> !status.get("playerId").equals(checkedPlayerId))
            .allSatisfy(status -> assertThat(status.get("checked")).isEqualTo(false));
    }
    @Test void minMaxParticipantsAndSessionPermissions() {
        createPlayers(2);
        session = app.createSession(room, "c0", "LIAR", "RANDOM").gameSessionId();
        error(NOT_ENOUGH_PLAYERS, () -> app.start(room, session, "c0"));
        for (int i = 2; i < 13; i++) app.join(room, "c" + i, "참가" + i, "MALE");
        error(TOO_MANY_PLAYERS, () -> app.start(room, session, "c0"));
        error(NOT_ROOM_HOST, () -> app.cancel(room, session, "c1"));
    }
    @Test void disconnectedHostWaitsWithoutTransferOrAutomaticClosure() {
        start(4);
        disconnect(ids.getFirst());
        var cleanup = new RoomCleanup(store, events, clock, Duration.ofHours(1));
        clock.now = clock.now.plus(Duration.ofMinutes(30));
        cleanup.clean();
        assertThat(app.state(room, "c1").players()).anySatisfy(player -> {
            assertThat(player.host()).isTrue();
            assertThat(player.connectionStatus()).isEqualTo("DISCONNECTED");
        });
        assertThat(events.count("ROOM_CLOSED")).isZero();
        assertThat(events.count("HOST_CHANGED")).isZero();
    }
    @Test void duplicateConcurrentVoteSucceedsOnce() throws Exception {
        start(4); voting();
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger duplicates = new AtomicInteger();
        Runnable vote = () -> {
            try { cast(ids.getFirst(), 1, ids.get(1)); successes.incrementAndGet(); }
            catch (DomainException ex) { assertThat(ex.code()).isEqualTo(ALREADY_VOTED); duplicates.incrementAndGet(); }
        };
        runTogether(List.of(vote, vote, vote, vote));
        assertThat(successes.get()).isEqualTo(1);
        assertThat(duplicates.get()).isEqualTo(3);
        assertThat(events.count("PLAYER_VOTED")).isEqualTo(1);
    }
    @Test void exclusionAndFinalVoteRaceAggregateExactlyOnce() throws Exception {
        start(4); voting();
        long excluded = ids.stream().filter(i -> i != liar && !i.equals(ids.getFirst())).findFirst().orElseThrow();
        long finalVoter = ids.stream().filter(i -> i != excluded).findFirst().orElseThrow();
        long accused = ids.stream().filter(i -> i != excluded && i != finalVoter).findFirst().orElseThrow();
        for (long id : ids) if (id != excluded && id != finalVoter) cast(id, 1, id == accused ? finalVoter : accused);
        disconnect(excluded); clock.now = clock.now.plusSeconds(121);
        runTogether(List.of(() -> app.exclude(room, session, "c0", excluded), () -> cast(finalVoter, 1, accused)));
        assertThat(events.count("VOTE_COMPLETED")).isEqualTo(1);
        assertThat(events.count("LIAR_REVEALED")).isEqualTo(1);
    }
    @Test void differentRoomsAreNotBlockedByOneRoomLock() throws Exception {
        createPlayers(3);
        long otherRoom = app.create("other", "다른방", "MALE").room().roomId();
        var acquired = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var blocked = executor.submit(() -> store.inRoom(room, r -> {
                acquired.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("lock timeout"); }
                catch (InterruptedException ex) { throw new AssertionError(ex); }
                return null;
            }));
            try {
                assertThat(acquired.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(app.join(otherRoom, "new", "입장", "MALE").nickname()).isEqualTo("입장");
            } finally { release.countDown(); }
            blocked.get(5, TimeUnit.SECONDS);
        }
    }
    void disconnect(long id) {
        store.inRoom(room, r -> { var p = r.players.get(id); p.connectionStatus = PlayerRuntime.ConnectionStatus.DISCONNECTED;
            p.disconnectedAt = clock.instant(); return null; });
    }
    void runTogether(List<Runnable> actions) throws Exception {
        try (var pool = Executors.newFixedThreadPool(actions.size())) {
            var ready = new CountDownLatch(actions.size()); var go = new CountDownLatch(1);
            var futures = actions.stream().map(action -> pool.submit(() -> {
                ready.countDown(); if (!go.await(5, TimeUnit.SECONDS)) throw new AssertionError("barrier timeout"); action.run(); return null;
            })).map(f -> (Future<?>) f).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); go.countDown();
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
        }
    }
}
