package cn.infinite.warwall.world.map;

import cn.infinite.warwall.world.room.CombatTeam;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** All scans and dirty-region tracking share the same arena bounds. */
public final class ArenaGeometry {
    public static final int RADIUS = 299;
    public static final int AXIS_HALF_WIDTH = 2;
    public static final int CENTRAL_RADIUS = 72;
    public static final int NEAR_RADIUS = 64;
    public static final int MIN_NEAR_WATER = 4;
    public static final int MIN_NEAR_WOOD = 16;
    public record ChunkPosition(int x, int z) { }
    private ArenaGeometry() { }

    public static boolean counted(int x, int z, int radius) {
        return Math.abs((long) x) <= radius && Math.abs((long) z) <= radius
                && Math.abs((long) x) > AXIS_HALF_WIDTH && Math.abs((long) z) > AXIS_HALF_WIDTH;
    }
    public static long columnsPerTeam(int radius) {
        long side = Math.max(0, radius - AXIS_HALF_WIDTH);
        return side * side;
    }
    public static List<ChunkPosition> chunksIn(int minX, int minZ, int maxX, int maxZ) {
        List<ChunkPosition> result = new ArrayList<>();
        for (int x = Math.floorDiv(minX, 16); x <= Math.floorDiv(maxX, 16); x++) {
            for (int z = Math.floorDiv(minZ, 16); z <= Math.floorDiv(maxZ, 16); z++) result.add(new ChunkPosition(x, z));
        }
        return result;
    }
    /** Visit all four quadrants in each ring instead of scanning one entire team first. */
    public static List<ChunkPosition> scanOrder() {
        List<ChunkPosition> result = chunksIn(-RADIUS, -RADIUS, RADIUS, RADIUS);
        result.sort(Comparator.comparingInt((ChunkPosition p) -> Math.max(distance(p.x), distance(p.z)))
                .thenComparingInt(p -> ResourceMetrics.quadrant(p.x >= 0 ? 1 : -1, p.z >= 0 ? 1 : -1).ordinal())
                .thenComparingInt(ChunkPosition::x).thenComparingInt(ChunkPosition::z));
        return List.copyOf(result);
    }
    private static int distance(int chunk) { return chunk >= 0 ? chunk : -chunk - 1; }
    public static int missingWoodColumns(long existing) {
        return (int) Math.max(0, MIN_NEAR_WOOD - existing);
    }
    public static boolean withinSpawnRadius(int x, int y, int z, CombatTeam team, int spawnY) {
        long dx = x - 3L * team.signX(), dz = z - 3L * team.signZ(), dy = (long) y - spawnY;
        return dx * dx + dz * dz + dy * dy <= NEAR_RADIUS * NEAR_RADIUS;
    }
    public static boolean nearResourcesReady(ResourceMetrics metrics) {
        for (CombatTeam team : CombatTeam.values()) {
            if (metrics.nearbyWater(team) < MIN_NEAR_WATER || metrics.nearbyWood(team) < MIN_NEAR_WOOD) return false;
        }
        return true;
    }
}

