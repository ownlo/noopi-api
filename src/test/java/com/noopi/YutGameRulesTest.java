package com.noopi;

import com.noopi.api.DomainException;
import com.noopi.api.ErrorCode;
import com.noopi.game.session.GameSessionRuntime;
import com.noopi.game.yut.*;
import com.noopi.realtime.RoomEvents;
import com.noopi.room.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static com.noopi.api.ErrorCode.*;
import static com.noopi.game.yut.YutGameRuntime.*;

class YutGameRulesTest {
    static class Dice extends Random {
        final Queue<Boolean> faces = new ArrayDeque<>();
        Dice() { super(12); }
        void result(int fronts) {
            for (int i = 0; i < 4; i++) faces.add(fronts == 1 ? i == 1 : i < fronts);
        }
        void backDo() { faces.addAll(List.of(true, false, false, false)); }
        @Override public boolean nextBoolean() { return faces.remove(); }
    }
    static class Events implements RoomEvents {
        final List<String> types = new ArrayList<>();
        @Override public void publish(RoomRuntime r, String type, Long id, Map<String,Object> payload) { types.add(type); }
        @Override public void closePlayer(long r, long p) {}
        @Override public void closeRoom(long r) {}
    }
    Dice dice;
    Events events;
    RoomStore store;
    RoomRuntime room;
    YutGameService service;
    YutStateProjection projection;
    @BeforeEach void setup() {
        dice = new Dice(); events = new Events();
        store = new RoomStore(new Random(1), Clock.systemUTC());
        room = store.create(new PlayerRuntime(1, "host", "방장", "MALE"));
        room.players.put(2L, new PlayerRuntime(2, "guest", "손님", "FEMALE"));
        service = YutGameService.withNakProbability(dice, events, 0); projection = new YutStateProjection(service);
        room.session = new GameSessionRuntime(100, "YUT", service.prepare("INDIVIDUAL"));
    }
    YutGameRuntime game() { return (YutGameRuntime) room.session.game; }
    void start() { store.inRoom(room.id, r -> { service.start(r); return null; }); }
    long current() { return game().currentPlayer(); }
    Piece own(int index) { return game().pieces.get(game().owner(current()) + "-" + index); }
    Piece enemy(int index) { return game().pieces.values().stream().filter(p -> !p.ownerId.equals(game().owner(current())) && p.id.endsWith("-" + index)).findFirst().orElseThrow(); }
    void place(Piece p, String node, String route) { p.status = PieceStatus.ON_BOARD; p.nodeId = node; p.route = route; }
    void error(ErrorCode expected, Runnable action) { assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo(expected)); }
    YutGameService.ThrowResult roll(int fronts) {
        return store.inRoom(room.id, r -> { dice.result(fronts); return service.throwYut(r, current()); });
    }
    YutGameService.ThrowResult rollBackDo() {
        return store.inRoom(room.id, r -> { dice.backDo(); return service.throwYut(r, current()); });
    }
    void token(String id) { store.inRoom(room.id, r -> { service.selectToken(r, current(), id); return null; }); }
    YutGameService.MoveResult move(Piece p) { return store.inRoom(room.id, r -> service.selectPiece(r, current(), p.id)); }
    YutGameService.MoveResult path(String id) { return store.inRoom(room.id, r -> service.selectPath(r, current(), id)); }
    @Test void validatesModesPlayersAndTeamReadiness() {
        error(INVALID_GAME_CONFIG, () -> service.prepare(null));
        error(INVALID_GAME_CONFIG, () -> service.prepare("OTHER"));
        room.players.remove(2L); error(NOT_ENOUGH_PLAYERS, this::start);
        for (long id = 2; id <= 5; id++) room.players.put(id, new PlayerRuntime(id, "c" + id, "말" + id, "MALE"));
        error(TOO_MANY_PLAYERS, this::start);
        room.players.remove(5L);
        room.session = new GameSessionRuntime(101, "YUT", service.prepare("TEAM"));
        error(INVALID_GAME_CONFIG, this::start);
        for (long id = 1; id <= 4; id++) service.selectTeam(room, id, id <= 2 ? "NOOPI" : "DAY");
        error(TEAM_FULL, () -> service.selectTeam(room, 1, "DAY"));
        assertThat(game().teams.get(1L)).isEqualTo(Team.NOOPI);
        start();
        assertThat(game().pieces).hasSize(8);
        for (int i = 0; i < 4; i++) assertThat(game().teams.get(game().turnOrder.get(i)))
            .isNotEqualTo(game().teams.get(game().turnOrder.get((i + 1) % 4)));
        error(GAME_ALREADY_STARTED, () -> service.selectTeam(room, 1, "DAY"));
    }
    @Test void randomOutcomesUseAllFourFacesAndBonusThrowsPrecedeTokens() {
        start();
        var yut = roll(4); var mo = roll(0); var backDo = rollBackDo();
        assertThat(yut.result()).isEqualTo(Result.YUT); assertThat(mo.result()).isEqualTo(Result.MO);
        assertThat(backDo.result()).isEqualTo(Result.BACK_DO); assertThat(backDo.steps()).isEqualTo(-1);
        assertThat(game().pendingBonusThrows).isZero();
        assertThat(game().tokens).hasSize(3);
        assertThat(game().throwResults).containsExactly(Result.YUT, Result.MO, Result.BACK_DO);
        error(INVALID_TURN_PHASE, () -> roll(1));
    }
    @Test void unmarkedSingleFrontIsOrdinaryDo() {
        start();
        var ordinaryDo = roll(1);
        assertThat(ordinaryDo.result()).isEqualTo(Result.DO);
        assertThat(ordinaryDo.steps()).isEqualTo(1);
    }
    @Test void nakDiscardsTheWholeTurnAndImmediatelyAdvances() {
        service = YutGameService.withNakProbability(dice, events, 1);
        projection = new YutStateProjection(service);
        start();
        long actor = current();
        game().tokens.put("saved", new MoveToken("saved", Result.GAE, 2));
        game().pendingBonusThrows = 2;

        var nak = store.inRoom(room.id, r -> service.throwYut(r, actor));

        assertThat(nak.result()).isEqualTo(Result.NAK);
        assertThat(nak.steps()).isZero();
        assertThat(nak.moveTokenId()).isNull();
        assertThat(nak.bonusThrowGranted()).isFalse();
        assertThat(game().currentPlayer()).isNotEqualTo(actor);
        assertThat(game().turnNo).isEqualTo(2);
        assertThat(game().tokens).isEmpty();
        assertThat(game().pendingBonusThrows).isZero();
        assertThat(game().turnPhase).isEqualTo(TurnPhase.WAITING_THROW);
        assertThat(game().lastThrow.result()).isEqualTo(Result.NAK);
        assertThat(events.types).endsWith("YUT_THROW_RESOLVED", "YUT_TURN_CHANGED");
        assertThat(projection.project(room, actor).get("lastThrow")).isEqualTo(game().lastThrow);
    }
    @Test void backDoWrapsFromFirstNodeToStartAndAppliesStackAndCapture() {
        start();
        Piece moving = own(1), ally = own(2), victim = enemy(1);
        place(moving, "OUTER_1", YutBoard.OUTER);
        place(ally, "OUTER_20", YutBoard.OUTER);
        place(victim, "OUTER_20", YutBoard.OUTER);
        token(rollBackDo().moveTokenId());
        var result = move(moving);
        assertThat(result.toNodeId()).isEqualTo("OUTER_20");
        assertThat(result.stackedPieceIds()).containsExactly(ally.id);
        assertThat(result.capturedPieceIds()).containsExactly(victim.id);
        assertThat(moving.group).containsExactly(moving.id, ally.id);
        assertThat(victim.status).isEqualTo(PieceStatus.READY);
        assertThat(result.bonusThrowGranted()).isTrue();
    }
    @Test void backDoFromStartFinishesTheWholeGroupAndCanWin() {
        start();
        Piece first = own(1), second = own(2);
        place(first, "OUTER_20", YutBoard.OUTER); place(second, "OUTER_20", YutBoard.OUTER);
        first.group = second.group = List.of(first.id, second.id);
        own(3).status = own(4).status = PieceStatus.FINISHED;
        token(rollBackDo().moveTokenId());
        var result = move(first);
        assertThat(result.finished()).isTrue();
        assertThat(first.status).isEqualTo(PieceStatus.FINISHED);
        assertThat(second.status).isEqualTo(PieceStatus.FINISHED);
        assertThat(room.session.status).isEqualTo(GameSessionRuntime.Status.FINISHED);
    }
    @Test void backDoTokenExpiresWhenOwnerHasNoOnBoardPiece() {
        start();
        long actor = current();
        token(rollBackDo().moveTokenId());
        assertThat(game().tokens).isEmpty();
        assertThat(game().selectedToken).isNull();
        assertThat(current()).isNotEqualTo(actor);
        assertThat(game().turnPhase).isEqualTo(TurnPhase.WAITING_THROW);
    }
    @Test void wrongTurnSpectatorAndDuplicateTokenAreRejected() {
        start(); long other = current() == 1 ? 2 : 1;
        error(NOT_CURRENT_TURN, () -> service.throwYut(room, other));
        error(NOT_GAME_PARTICIPANT, () -> service.throwYut(room, 99));
        var bonus = roll(4);
        error(INVALID_TURN_PHASE, () -> token(bonus.moveTokenId()));
        roll(1); token(bonus.moveTokenId());
        error(ACTION_ALREADY_PROCESSED, () -> token(bonus.moveTokenId()));
        error(PIECE_NOT_ELIGIBLE, () -> service.selectPiece(room, current(), enemy(1).id));
        move(own(1));
        error(MOVE_TOKEN_ALREADY_USED, () -> token(bonus.moveTokenId()));
        error(MOVE_TOKEN_NOT_FOUND, () -> token("missing"));
    }
    @Test void captureResetsWholeGroupAndDefersBonusUntilRemainingTokensAreConsumed() {
        start();
        Piece one = enemy(1), two = enemy(2);
        place(one, "OUTER_2", YutBoard.OUTER); place(two, "OUTER_2", YutBoard.OUTER);
        one.group = two.group = List.of(one.id, two.id);
        var yut = roll(4); var gae = roll(2);
        long actor = current(); token(gae.moveTokenId()); var capture = move(own(1));
        assertThat(capture.capturedPieceIds()).containsExactly(one.id, two.id);
        assertThat(game().pendingBonusThrows).isEqualTo(1);
        assertThat(game().turnPhase).isEqualTo(TurnPhase.WAITING_MOVE);
        assertThat(one.status).isEqualTo(PieceStatus.READY); assertThat(one.group).containsExactly(one.id);
        assertThat(two.nodeId).isNull();
        token(yut.moveTokenId()); move(own(2));
        assertThat(current()).isEqualTo(actor); assertThat(game().turnPhase).isEqualTo(TurnPhase.WAITING_THROW);
        roll(1); assertThat(game().pendingBonusThrows).isZero();
    }
    @Test void passingOpponentDoesNotCaptureAndTurnAdvances() {
        start(); var victim = enemy(1); place(victim, "OUTER_1", YutBoard.OUTER);
        long actor = current(); token(roll(2).moveTokenId()); var result = move(own(1));
        assertThat(result.capturedPieceIds()).isEmpty(); assertThat(victim.status).isEqualTo(PieceStatus.ON_BOARD);
        assertThat(current()).isNotEqualTo(actor); assertThat(game().turnNo).isEqualTo(2);
    }
    @Test void stackingKeepsStableRepresentativeAndMovesWholeGroup() {
        start(); Piece first = own(1), second = own(2); place(first, "OUTER_2", YutBoard.OUTER);
        var yut = roll(4); token(roll(2).moveTokenId());
        var stacked = move(second); assertThat(stacked.stackedPieceIds()).containsExactly(first.id);
        assertThat(first.group).containsExactly(first.id, second.id); assertThat(second.group).isEqualTo(first.group);
        assertThat(service.eligiblePieces(game())).contains(first.id).doesNotContain(second.id);
        token(yut.moveTokenId()); var moved = move(first);
        assertThat(moved.pieceIds()).containsExactly(first.id, second.id);
        assertThat(first.nodeId).isEqualTo("OUTER_6"); assertThat(second.nodeId).isEqualTo(first.nodeId);
    }
    @Test void shortcutSelectionPersistsAcrossStateReadsAndCanReachHomeFromCenter() {
        start(); var piece = own(1); place(piece, "OUTER_5", YutBoard.OUTER);
        var yut = roll(4); token(roll(3).moveTokenId());
        assertThat(move(piece)).isNull();
        Map<?, ?> action = (Map<?, ?>) projection.project(room, current()).get("myAction");
        assertThat(action.get("type")).isEqualTo("SELECT_PATH");
        assertThat(action.get("pieceId")).isEqualTo(piece.id);
        error(PATH_NOT_ELIGIBLE, () -> path(null));
        error(PATH_NOT_ELIGIBLE, () -> path(YutBoard.B));
        assertThat(path(YutBoard.A).toNodeId()).isEqualTo("CENTER_3");
        token(yut.moveTokenId()); assertThat(move(piece)).isNull();
        assertThat(path(YutBoard.HOME).finished()).isTrue();
    }
    @Test void shortcutsOnlyBranchWhenStartingExactlyAtJunction() {
        Piece p = new Piece("test", "1");
        place(p, "OUTER_4", YutBoard.OUTER);
        assertThat(YutBoard.move(p, 3, YutBoard.OUTER).nodeId()).isEqualTo("OUTER_7");
        place(p, "OUTER_10", YutBoard.OUTER);
        assertThat(YutBoard.move(p, 5, YutBoard.B).nodeId()).isEqualTo("CENTER_9");
        place(p, "CENTER_2", YutBoard.A);
        assertThat(YutBoard.move(p, 3, YutBoard.A).nodeId()).isEqualTo("CENTER_5");
        place(p, "CENTER_5", YutBoard.A);
        assertThat(YutBoard.move(p, 2, YutBoard.A).nodeId()).isEqualTo("OUTER_16");
    }
    @Test void backDoRetracesTheActualShortcutAtMergedOuterNode() {
        Piece p = new Piece("test", "1");
        var toJunction = YutBoard.move(p, 5, YutBoard.OUTER);
        apply(p, toJunction);
        var throughShortcut = YutBoard.move(p, 6, YutBoard.A);
        apply(p, throughShortcut);
        assertThat(p.nodeId).isEqualTo("OUTER_15");
        var back = YutBoard.move(p, -1, p.route);
        assertThat(back.nodeId()).isEqualTo("CENTER_5");
        assertThat(back.route()).isEqualTo(YutBoard.A);
    }
    @Test void landingOnHomeDoesNotFinishButPassingItFinishesWholeGroupAndWins() {
        start(); Piece first = own(1), second = own(2);
        place(first, "OUTER_19", YutBoard.OUTER); place(second, "OUTER_19", YutBoard.OUTER);
        first.group = second.group = List.of(first.id, second.id);
        own(3).status = own(4).status = PieceStatus.FINISHED;
        var yut = roll(4); token(roll(1).moveTokenId());
        assertThat(move(first).finished()).isFalse(); assertThat(first.nodeId).isEqualTo("OUTER_20");
        token(yut.moveTokenId()); assertThat(move(first).finished()).isTrue();
        assertThat(room.session.status).isEqualTo(GameSessionRuntime.Status.FINISHED);
        assertThat(second.status).isEqualTo(PieceStatus.FINISHED);
        assertThat(projection.project(room, current())).containsKey("winnerPlayer").doesNotContainKey("winnerTeam");
        assertThat(events.types.stream().filter("GAME_FINISHED"::equals).count()).isEqualTo(1);
        error(GAME_SESSION_ALREADY_FINISHED, () -> service.throwYut(room, current()));
    }
    @Test void projectionProvidesNoActionsToOtherPlayersAndCancelIsRestorable() {
        start(); assertThat(projection.project(room, current() == 1 ? 2 : 1).get("myAction")).isNull();
        assertThat(projection.project(room, 99).get("myAction")).isNull();
        token(roll(2).moveTokenId());
        assertThat(((Map<?, ?>) projection.project(room, current()).get("myAction")).get("type")).isEqualTo("SELECT_PIECE");
        service.cancel(room, "HOST_CANCELLED");
        assertThat(projection.project(room, current())).containsEntry("phase", "CANCELLED").containsEntry("reason", "HOST_CANCELLED");
    }
    @Test void simultaneousMovesConsumeOneTokenOnce() throws Exception {
        start(); long actor = current(); String piece = own(1).id; token(roll(2).moveTokenId());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var go = new CountDownLatch(1);
            Callable<Boolean> request = () -> { go.await(); try {
                store.inRoom(room.id, r -> service.selectPiece(r, actor, piece)); return true;
            } catch (DomainException e) { return false; } };
            var a = pool.submit(request); var b = pool.submit(request); go.countDown();
            assertThat(List.of(a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        }
        assertThat(events.types.stream().filter("YUT_PIECE_MOVED"::equals).count()).isEqualTo(1);
    }
    @Test void completeIndividualAndTeamGamesMaintainAllPieceInvariants() {
        var choices = new Random(451);
        for (int run = 0; run < 12; run++) {
            setup();
            if (run % 2 == 1) {
                room.players.put(3L, new PlayerRuntime(3, "c3", "세번째", "MALE"));
                room.players.put(4L, new PlayerRuntime(4, "c4", "네번째", "MALE"));
                room.session = new GameSessionRuntime(100, "YUT", service.prepare("TEAM"));
                for (long id = 1; id <= 4; id++) service.selectTeam(room, id, id % 2 == 0 ? "DAY" : "NOOPI");
            } else {
                for (int extra = 0; extra < (run / 2) % 3; extra++) {
                    long id = 3 + extra;
                    room.players.put(id, new PlayerRuntime(id, "c" + id, "말" + id, "MALE"));
                }
            }
            start();
            for (int action = 0; action < 4000 && game().phase == Phase.PLAYING; action++) {
                if (game().turnPhase == TurnPhase.WAITING_THROW) roll(choices.nextInt(5));
                else if (game().turnPhase == TurnPhase.WAITING_PATH_SELECTION) {
                    var paths = YutBoard.paths(game().pieces.get(game().selectedPiece));
                    path(paths.get(choices.nextInt(paths.size())));
                } else if (game().selectedToken == null) token(game().tokens.keySet().iterator().next());
                else {
                    var eligible = service.eligiblePieces(game());
                    move(game().pieces.get(eligible.get(choices.nextInt(eligible.size()))));
                }
                for (var p : game().pieces.values()) {
                    assertThat(p.group).contains(p.id).doesNotHaveDuplicates();
                    assertThat(p.nodeId != null).isEqualTo(p.status == PieceStatus.ON_BOARD);
                    for (String id : p.group) {
                        Piece member = game().pieces.get(id);
                        assertThat(member.ownerId).isEqualTo(p.ownerId);
                        assertThat(member.nodeId).isEqualTo(p.nodeId);
                        assertThat(member.status).isEqualTo(p.status);
                        assertThat(member.group).isEqualTo(p.group);
                    }
                }
            }
            assertThat(game().phase).as("game %s must complete", run).isEqualTo(Phase.FINISHED);
            assertThat(game().pieces.values().stream().filter(p -> p.ownerId.equals(game().winnerOwner) && p.status == PieceStatus.FINISHED).count()).isEqualTo(4);
            assertThat(projection.project(room, current())).containsKey(run % 2 == 0 ? "winnerPlayer" : "winnerTeam");
        }
    }
    @Test void concurrentTeamSelectionCannotOverfillLastSeat() throws Exception {
        room.players.put(3L, new PlayerRuntime(3, "third", "세번째", "MALE"));
        room.players.put(4L, new PlayerRuntime(4, "fourth", "네번째", "MALE"));
        room.session = new GameSessionRuntime(100, "YUT", service.prepare("TEAM"));
        service.selectTeam(room, 1, "NOOPI");
        try (var pool = Executors.newFixedThreadPool(2)) {
            var go = new CountDownLatch(1);
            var futures = List.of(2L, 3L).stream().map(id -> pool.submit(() -> {
                go.await();
                try { store.inRoom(room.id, r -> { service.selectTeam(r, id, "NOOPI"); return null; }); return true; }
                catch (DomainException e) { assertThat(e.code()).isEqualTo(TEAM_FULL); return false; }
            })).toList();
            go.countDown();
            assertThat(List.of(futures.get(0).get(5, TimeUnit.SECONDS), futures.get(1).get(5, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(true, false);
        }
        assertThat(game().teams.values().stream().filter(t -> t == Team.NOOPI).count()).isEqualTo(2);
    }
    private void apply(Piece piece, YutBoard.Destination destination) {
        piece.status = destination.finished() ? PieceStatus.FINISHED : PieceStatus.ON_BOARD;
        piece.nodeId = destination.nodeId();
        piece.route = destination.route();
        piece.history = new ArrayList<>(destination.history());
    }
}
