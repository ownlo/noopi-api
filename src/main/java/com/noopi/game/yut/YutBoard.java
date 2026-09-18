package com.noopi.game.yut;

import java.util.*;
import static com.noopi.game.yut.YutGameRuntime.PieceStatus.*;

/** Directed board graph. Branches can be selected only at a move's starting node. */
public final class YutBoard {
    public static final String OUTER = "OUTER";
    public static final String A = "CENTER_SHORTCUT_A";
    public static final String B = "CENTER_SHORTCUT_B";
    public static final String HOME = "CENTER_SHORTCUT_HOME";
    public static final String FINISH = "FINISH";
    private static final Map<String, String> OUTER_EDGES = new LinkedHashMap<>();
    private static final Map<String, String> A_EDGES = edges("OUTER_5", "CENTER_1", "CENTER_2", "CENTER_3", "CENTER_4", "CENTER_5", "OUTER_15");
    private static final Map<String, String> B_EDGES = edges("OUTER_10", "CENTER_6", "CENTER_7", "CENTER_3", "CENTER_8", "CENTER_9", "OUTER_20");
    static {
        for (int i = 1; i < 20; i++) OUTER_EDGES.put("OUTER_" + i, "OUTER_" + (i + 1));
        OUTER_EDGES.put("OUTER_20", FINISH);
    }
    private YutBoard() {}
    private static Map<String, String> edges(String... nodes) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < nodes.length - 1; i++) result.put(nodes[i], nodes[i + 1]);
        return Map.copyOf(result);
    }
    public static List<String> paths(YutGameRuntime.Piece piece) {
        if (piece.status == READY) return List.of(OUTER);
        if ("OUTER_5".equals(piece.nodeId)) return List.of(OUTER, A);
        if ("OUTER_10".equals(piece.nodeId)) return List.of(OUTER, B);
        if ("CENTER_3".equals(piece.nodeId) && A.equals(piece.route)) return List.of(A, HOME);
        return List.of(piece.route);
    }
    public record Position(String nodeId, String route) {}
    public record Destination(String nodeId, String route, List<Position> history) {
        public boolean finished() { return nodeId == null; }
    }
    public static Destination move(YutGameRuntime.Piece piece, int steps, String path) {
        if (steps == -1) return moveBack(piece);
        if (steps < 0) throw new IllegalArgumentException("Unsupported negative move");
        String node = piece.nodeId;
        String route = path;
        List<Position> history = new ArrayList<>(piece.history);
        if (node != null && (history.isEmpty() || !node.equals(history.getLast().nodeId()))) {
            history.add(new Position(node, piece.route));
        }
        for (int i = 0; i < steps; i++) {
            if (node == null) node = "OUTER_1";
            else {
                Map<String, String> diagonal = A.equals(route) ? A_EDGES : B.equals(route) || HOME.equals(route) ? B_EDGES : Map.of();
                String next = diagonal.get(node);
                if (next == null) { next = OUTER_EDGES.get(node); route = OUTER; }
                if (FINISH.equals(next)) return new Destination(null, OUTER, List.of());
                if (next == null) throw new IllegalStateException("Unknown board node");
                node = next;
                if (node.startsWith("OUTER_")) route = OUTER;
            }
            history.add(new Position(node, route));
        }
        return new Destination(node, route, List.copyOf(history));
    }
    private static Destination moveBack(YutGameRuntime.Piece piece) {
        if (piece.status != ON_BOARD || piece.nodeId == null) throw new IllegalArgumentException("Back-do requires an on-board piece");
        if ("OUTER_20".equals(piece.nodeId)) return new Destination(null, OUTER, List.of());
        if ("OUTER_1".equals(piece.nodeId)) {
            return new Destination("OUTER_20", OUTER, List.of(new Position("OUTER_20", OUTER)));
        }
        List<Position> history = new ArrayList<>(piece.history);
        if (!history.isEmpty() && piece.nodeId.equals(history.getLast().nodeId())) history.removeLast();
        Position previous = history.isEmpty() ? previous(piece) : history.getLast();
        if (history.isEmpty()) history.add(previous);
        return new Destination(previous.nodeId(), previous.route(), List.copyOf(history));
    }
    private static Position previous(YutGameRuntime.Piece piece) {
        String node = piece.nodeId;
        if (node.startsWith("OUTER_")) {
            int index = Integer.parseInt(node.substring("OUTER_".length()));
            if (index > 1) return new Position("OUTER_" + (index - 1), OUTER);
        }
        List<String> route = switch (piece.route) {
            case A -> List.of("OUTER_5", "CENTER_1", "CENTER_2", "CENTER_3", "CENTER_4", "CENTER_5", "OUTER_15");
            case B -> List.of("OUTER_10", "CENTER_6", "CENTER_7", "CENTER_3", "CENTER_8", "CENTER_9", "OUTER_20");
            case HOME -> List.of("CENTER_3", "CENTER_8", "CENTER_9", "OUTER_20");
            default -> List.of();
        };
        int index = route.indexOf(node);
        if (index > 0) {
            String previous = route.get(index - 1);
            String previousRoute = previous.startsWith("OUTER_") ? OUTER : piece.route;
            return new Position(previous, previousRoute);
        }
        throw new IllegalStateException("Unknown previous board node");
    }
}
