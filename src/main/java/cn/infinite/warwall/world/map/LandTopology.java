package cn.infinite.warwall.world.map;

import cn.infinite.warwall.world.room.CombatTeam;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;

/** Whole-arena connected components, including connections across chunk boundaries. Worker-only CPU work. */
public final class LandTopology {
    public record Result(Map<CombatTeam, Long> largestGentleArea, Map<CombatTeam, Long> islandColumns) { }
    private static final int SIDE = ArenaGeometry.RADIUS * 2 + 1;
    private LandTopology() { }
    public static Result analyze(ResourceMetrics metrics, int islandMaxArea) {
        int size = SIDE * SIDE;
        int[] heights = new int[size]; Arrays.fill(heights, Integer.MIN_VALUE);
        byte[] types = new byte[size]; // 0 unknown, 1 dry land, 2 water, 3 non-usable surface
        for (ResourceMetrics.SurfaceCell cell : metrics.cells()) {
            if (!ArenaGeometry.counted(cell.x(), cell.z(), ArenaGeometry.RADIUS)) continue;
            int id = id(cell.x(), cell.z()); heights[id] = cell.groundY();
            types[id] = (byte) (cell.land() ? 1 : cell.water() ? 2 : 3);
        }
        EnumMap<CombatTeam, Long> gentle = zeros(), islands = zeros();
        int[] queue = new int[size]; boolean[] seen = new boolean[size];
        for (int start = 0; start < size; start++) {
            if (types[start] != 1 || seen[start]) continue;
            CombatTeam team = team(start); int end = 1; queue[0] = start; seen[start] = true;
            int waterEdges = 0, edges = 0; boolean openBoundary = false;
            for (int head = 0; head < end; head++) {
                checkInterrupted(head);
                int current = queue[head];
                for (int direction = 0; direction < 4; direction++) {
                    int next = neighbour(current, direction);
                    // A clipped peninsula is not a proven island. Unknown/arena/team edges are open.
                    if (next < 0 || types[next] == 0 || team(next) != team) { openBoundary = true; continue; }
                    if (types[next] != 1) { edges++; if (types[next] == 2) waterEdges++; }
                    else if (!seen[next]) { seen[next] = true; queue[end++] = next; }
                }
            }
            if (!openBoundary && end <= islandMaxArea && edges > 0 && waterEdges >= edges * 0.6) islands.merge(team, (long) end, Long::sum);
        }
        Arrays.fill(seen, false);
        for (int start = 0; start < size; start++) {
            if (types[start] != 1 || seen[start]) continue;
            CombatTeam team = team(start); int end = 1; queue[0] = start; seen[start] = true;
            for (int head = 0; head < end; head++) {
                checkInterrupted(head); int current = queue[head];
                for (int direction = 0; direction < 4; direction++) {
                    int next = neighbour(current, direction);
                    if (next < 0 || types[next] != 1 || seen[next] || team(next) != team
                            || Math.abs((long) heights[current] - heights[next]) > 2) continue;
                    seen[next] = true; queue[end++] = next;
                }
            }
            gentle.merge(team, (long) end, Math::max);
        }
        return new Result(Map.copyOf(gentle), Map.copyOf(islands));
    }
    private static EnumMap<CombatTeam, Long> zeros() {
        EnumMap<CombatTeam, Long> result = new EnumMap<>(CombatTeam.class);
        for (CombatTeam team : CombatTeam.values()) result.put(team, 0L);
        return result;
    }
    private static void checkInterrupted(int head) {
        if ((head & 1023) == 0 && Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("地形连通性计算已取消");
    }
    private static int id(int x, int z) { return (x + ArenaGeometry.RADIUS) * SIDE + z + ArenaGeometry.RADIUS; }
    private static CombatTeam team(int id) { return ResourceMetrics.quadrant(id / SIDE - ArenaGeometry.RADIUS, id % SIDE - ArenaGeometry.RADIUS); }
    private static int neighbour(int id, int direction) {
        // Avoid allocating a four-element array for every cell on both graph passes.
        return switch (direction) {
            case 0 -> id >= SIDE ? id - SIDE : -1;
            case 1 -> id < SIDE * (SIDE - 1) ? id + SIDE : -1;
            case 2 -> id % SIDE > 0 ? id - 1 : -1;
            case 3 -> id % SIDE < SIDE - 1 ? id + 1 : -1;
            default -> throw new IllegalArgumentException("Invalid neighbour direction");
        };
    }
}

