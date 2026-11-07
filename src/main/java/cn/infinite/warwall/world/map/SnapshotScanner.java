package cn.infinite.warwall.world.map;

import cn.infinite.warwall.world.room.CombatTeam;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.block.data.Waterlogged;

/** Snapshot-only CPU work: no live World, Chunk or Block access on worker threads. */
public final class SnapshotScanner {
    @FunctionalInterface public interface Biomes { String at(int x, int y, int z); }
    private static final Resource[] RESOURCES = new Resource[Material.values().length];
    private static final boolean[] COVER = new boolean[Material.values().length];
    static {
        for (Material material : Material.values()) {
            String name = material.name();
            RESOURCES[material.ordinal()] = switch (material) {
                case COAL_ORE, DEEPSLATE_COAL_ORE -> Resource.COAL;
                case IRON_ORE, DEEPSLATE_IRON_ORE -> Resource.IRON;
                case GOLD_ORE, DEEPSLATE_GOLD_ORE -> Resource.GOLD;
                case DIAMOND_ORE, DEEPSLATE_DIAMOND_ORE -> Resource.DIAMOND;
                default -> name.endsWith("_LOG") || name.endsWith("_STEM") ? Resource.WOOD : null;
            };
            COVER[material.ordinal()] = name.endsWith("_LEAVES") || RESOURCES[material.ordinal()] == Resource.WOOD
                    || switch (material) {
                        case AIR, CAVE_AIR, VOID_AIR, SHORT_GRASS, TALL_GRASS, FERN, LARGE_FERN,
                                DEAD_BUSH, VINE, GLOW_LICHEN, SNOW, DANDELION, POPPY, BLUE_ORCHID,
                                ALLIUM, AZURE_BLUET, RED_TULIP, ORANGE_TULIP, WHITE_TULIP, PINK_TULIP,
                                OXEYE_DAISY, CORNFLOWER, LILY_OF_THE_VALLEY, SUNFLOWER, LILAC,
                                ROSE_BUSH, PEONY, BROWN_MUSHROOM, RED_MUSHROOM -> true;
                        default -> false;
                    };
        }
    }
    private SnapshotScanner() { }

    /** Cheap surface gate; rejected terrain never enters the underground block-count pass. */
    public static ResourceMetrics scanTerrain(ChunkSnapshot snapshot, int radius, int minY, int maxY) {
        return scan(snapshot, radius, minY, maxY, 0, false);
    }
    public static ResourceMetrics scan(ChunkSnapshot snapshot, int radius, int minY, int maxY, int spawnY) {
        return scan(snapshot, radius, minY, maxY, spawnY, (x, y, z) -> snapshot.getBiome(x, y, z).getKey().toString());
    }
    public static ResourceMetrics scan(ChunkSnapshot snapshot, int radius, int minY, int maxY, int spawnY, Biomes biomes) {
        return scan(snapshot, radius, minY, maxY, spawnY, true, biomes);
    }
    private static ResourceMetrics scan(ChunkSnapshot snapshot, int radius, int minY, int maxY, int spawnY, boolean resources) {
        return scan(snapshot, radius, minY, maxY, spawnY, resources, (x, y, z) -> "minecraft:plains");
    }
    private static ResourceMetrics scan(ChunkSnapshot snapshot, int radius, int minY, int maxY, int spawnY, boolean resources, Biomes biomes) {
        if (minY >= maxY) throw new IllegalArgumentException("无效世界高度");
        ResourceMetrics result = new ResourceMetrics();
        int firstSection = Math.floorDiv(minY, 16), lastSection = Math.floorDiv(maxY - 1, 16);
        boolean[] empty = new boolean[lastSection - firstSection + 1];
        // Paper 1.21.11 按从世界最低节开始的数组下标读取空节标记；方块读取仍使用绝对 Y。
        if (resources) for (int index = 0; index < empty.length; index++) empty[index] = snapshot.isSectionEmpty(index);
        for (int bx = 0; bx < 16; bx++) for (int bz = 0; bz < 16; bz++) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("快照扫描已取消");
            int x = snapshot.getX() * 16 + bx, z = snapshot.getZ() * 16 + bz;
            if (!ArenaGeometry.counted(x, z, radius)) continue;
            CombatTeam team = ResourceMetrics.quadrant(x, z);
            int top = Math.min(maxY - 1, snapshot.getHighestBlockYAt(bx, bz));
            int ground = top;
            while (ground >= minY && COVER[snapshot.getBlockType(bx, ground, bz).ordinal()]) ground--;
            boolean wet = ground >= minY && wetSurface(snapshot, bx, ground, bz);
            boolean land = ground >= minY && ground <= 146 && !wet;
            boolean nearWater = false, nearWood = false;
            if (resources) {
                result.cell(x, z, ground, land, wet && snapshot.getBlockType(bx, ground, bz) != Material.LAVA);
                String biome = biomes.at(bx, Math.clamp(ground, minY, maxY - 1), bz);
                for (TerrainKind kind : TerrainKind.at(biome, ground)) result.terrain(team, kind, 1);
                long dx = x - 3L * team.signX(), dz = z - 3L * team.signZ();
                long horizontal = dx * dx + dz * dz;
                boolean near = horizontal <= ArenaGeometry.NEAR_RADIUS * ArenaGeometry.NEAR_RADIUS;
                boolean outside = Math.abs(x) > ArenaGeometry.CENTRAL_RADIUS || Math.abs(z) > ArenaGeometry.CENTRAL_RADIUS;
                int from = outside ? minY : Math.max(minY, Math.max(ground - 16, spawnY - ArenaGeometry.NEAR_RADIUS));
                int to = outside ? top : Math.min(top, spawnY + ArenaGeometry.NEAR_RADIUS);
                if (outside || near) for (int section = Math.floorDiv(from, 16); section <= Math.floorDiv(to, 16); section++) {
                    if (empty[section - firstSection]) continue;
                    for (int y = Math.max(from, section * 16); y <= Math.min(to, section * 16 + 15); y++) {
                        Material material = snapshot.getBlockType(bx, y, bz);
                        Resource resource = RESOURCES[material.ordinal()];
                        if (near && y >= ground - 16 && y <= ground + 16
                                && horizontal + (long) (y - spawnY) * (y - spawnY) <= ArenaGeometry.NEAR_RADIUS * ArenaGeometry.NEAR_RADIUS) {
                            nearWater |= material == Material.WATER;
                            nearWood |= resource == Resource.WOOD;
                        }
                        if (outside && resource != null) {
                            result.resource(team, resource, 1);
                            if (resource != Resource.WOOD && exposed(snapshot, bx, y, bz, minY, maxY)) result.exposed(team, resource, 1);
                        }
                    }
                }
            }
            result.surface(team, land, nearWater, nearWood);
        }
        return result;
    }
    // Conservative at horizontal chunk edges: unknown neighbours never create a false exposure bonus.
    private static boolean exposed(ChunkSnapshot snapshot, int x, int y, int z, int minY, int maxY) {
        return y > minY && air(snapshot.getBlockType(x, y - 1, z))
                || y + 1 < maxY && air(snapshot.getBlockType(x, y + 1, z))
                || x > 0 && air(snapshot.getBlockType(x - 1, y, z))
                || x < 15 && air(snapshot.getBlockType(x + 1, y, z))
                || z > 0 && air(snapshot.getBlockType(x, y, z - 1))
                || z < 15 && air(snapshot.getBlockType(x, y, z + 1));
    }
    public static boolean air(Material material) { return material == Material.AIR || material == Material.CAVE_AIR || material == Material.VOID_AIR; }
    private static boolean wetSurface(ChunkSnapshot snapshot, int x, int y, int z) {
        Material material = snapshot.getBlockType(x, y, z);
        if (material == Material.WATER || material == Material.LAVA || material == Material.KELP
                || material == Material.KELP_PLANT || material == Material.SEAGRASS || material == Material.TALL_SEAGRASS
                || material == Material.LILY_PAD || material == Material.BUBBLE_COLUMN || material == Material.ICE
                || material == Material.FROSTED_ICE) return true;
        // Waterlogged surfaces (e.g. mangrove roots) are not dry land.
        return snapshot.getBlockData(x, y, z) instanceof Waterlogged waterlogged && waterlogged.isWaterlogged();
    }
}

