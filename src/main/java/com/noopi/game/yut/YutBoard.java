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
    public record Destination(String nodeId, String route) { public boolean finished() { return nodeId == null; } }
    public static Destination move(YutGameRuntime.Piece piece, int steps, String path) {
        String node = piece.nodeId;
        String route = path;
        for (int i = 0; i < steps; i++) {
            if (node == null) node = "OUTER_1";
            else {
                Map<String, String> diagonal = A.equals(route) ? A_EDGES : B.equals(route) || HOME.equals(route) ? B_EDGES : Map.of();
                String next = diagonal.get(node);
                if (next == null) { next = OUTER_EDGES.get(node); route = OUTER; }
                if (FINISH.equals(next)) return new Destination(null, OUTER);
                if (next == null) throw new IllegalStateException("Unknown board node");
                node = next;
                if (node.startsWith("OUTER_")) route = OUTER;
            }
        }
        return new Destination(node, route);
    }
}
