package cn.infinite.warwall.world.map;

import cn.infinite.warwall.world.room.CombatTeam;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.inventory.ItemStack;
import org.bukkit.structure.Structure;

/** 使用运行中 Minecraft 自带的结构模板，不随插件分发第三方数据包资源。 */
public final class CentralStructures {
    private CentralStructures() { }
    @FunctionalInterface public interface DirtyArea { void mark(int minX, int minZ, int maxX, int maxZ); }
    public static void plan(World world, int centerY, boolean village, boolean city, Random random,
                            Consumer<Runnable> edits, DirtyArea dirty) {
        if (village) {
            // 同一局使用同一种屋型，避免排除统计区中的可拆建筑材料形成额外资源差距。
            Structure house = load("village/plains/houses/plains_small_house_" + (1 + random.nextInt(4)));
            for (CombatTeam team : CombatTeam.values()) {
                int x = team.signX() * 28 - (team.signX() < 0 ? house.getSize().getBlockX() : 0);
                int z = team.signZ() * 28 - (team.signZ() < 0 ? house.getSize().getBlockZ() : 0);
                int y = centerY;
                checkCentralBounds(x, z, house.getSize().getBlockX(), house.getSize().getBlockZ());
                dirty.mark(x, z, x + house.getSize().getBlockX() - 1, z + house.getSize().getBlockZ() - 1);
                dirty.mark(team.signX() * 25, team.signZ() * 25, team.signX() * 25, team.signZ() * 25);
                normalizeArea(world, x, y, z, house.getSize().getBlockX(), house.getSize().getBlockY(), house.getSize().getBlockZ(), edits);
                edits.accept(() -> house.place(new Location(world, x, y, z), false, StructureRotation.NONE, Mirror.NONE, 0, 1, random));
                cleanContainers(world, x, y, z, house, edits);
                edits.accept(() -> reward(world, team.signX() * 25, y + 1, team.signZ() * 25,
                        List.of(new ItemStack(Material.IRON_INGOT, 8), new ItemStack(Material.BREAD, 16), new ItemStack(Material.EMERALD, 4))));
            }
        }
        if (city) {
            Structure center = load("ancient_city/city_center/city_center_1");
            int sx = center.getSize().getBlockX(), sy = center.getSize().getBlockY(), sz = center.getSize().getBlockZ();
            if (sx > 140 || sz > 140 || sy > 64) throw new IllegalStateException("中央古城模板尺寸超出地图准备上限。");
            int x = -sx / 2, z = -sz / 2, y = Math.max(world.getMinHeight() + 4, -50);
            checkCentralBounds(x, z, sx, sz);
            dirty.mark(x, z, x + sx - 1, z + sz - 1);
            normalizeArea(world, x, y, z, sx, sy, sz, edits);
            edits.accept(() -> center.place(new Location(world, x, y, z), false, StructureRotation.NONE, Mirror.NONE, 0, 1, random));
            cleanContainers(world, x, y, z, center, edits);
            for (CombatTeam team : CombatTeam.values()) {
                int px = team.signX() * 18, pz = team.signZ() * 18;
                // 每区独立入口，开局墙不拆除。奖励数相同，不能从相邻象限提前绕入。
                dirty.mark(px - 2, pz, px + 2, pz + 2);
                edits.accept(() -> {
                    int entranceY = Math.max(centerY + 3, world.getHighestBlockYAt(px, pz) + 3);
                    for (int by = y + 1; by <= entranceY; by++) {
                        for (int dx = 0; dx < 2; dx++) for (int dz = 0; dz < 2; dz++) world.getBlockAt(px + dx, by, pz + dz).setType(Material.AIR, false);
                        world.getBlockAt(px - team.signX() * 2, by, pz).setType(Material.DEEPSLATE_BRICKS, false);
                        world.getBlockAt(px - team.signX(), by, pz).setType(Material.LADDER, false);
                        var ladder = (org.bukkit.block.data.type.Ladder) world.getBlockAt(px - team.signX(), by, pz).getBlockData();
                        ladder.setFacing(team.signX() > 0 ? org.bukkit.block.BlockFace.EAST : org.bukkit.block.BlockFace.WEST);
                        world.getBlockAt(px - team.signX(), by, pz).setBlockData(ladder, false);
                    }
                    reward(world, px, y + 1, pz, List.of(new ItemStack(Material.DIAMOND, 3), new ItemStack(Material.GOLDEN_APPLE, 2), new ItemStack(Material.EXPERIENCE_BOTTLE, 12)));
                });
            }
        }
    }
    private static Structure load(String key) {
        Structure structure = Bukkit.getStructureManager().loadStructure(NamespacedKey.minecraft(key));
        if (structure == null) throw new IllegalStateException("Minecraft 结构模板不可用：" + key);
        return structure;
    }
    private static void checkCentralBounds(int x, int z, int sx, int sz) {
        if (x < -ArenaGeometry.CENTRAL_RADIUS || z < -ArenaGeometry.CENTRAL_RADIUS
                || x + sx - 1 > ArenaGeometry.CENTRAL_RADIUS || z + sz - 1 > ArenaGeometry.CENTRAL_RADIUS) {
            throw new IllegalStateException("中央模板超出资源统计排除区域");
        }
    }
    private static void normalizeArea(World world, int x, int y, int z, int sx, int sy, int sz, Consumer<Runnable> edits) {
        if (sx < 1 || sz < 1 || sy < 1 || sx > 140 || sz > 140 || sy > 64) throw new IllegalStateException("结构尺寸无效。");
        for (int dx = 0; dx < sx; dx++) for (int dz = 0; dz < sz; dz++) {
            int px = x + dx, pz = z + dz;
            edits.accept(() -> {
                world.getBlockAt(px, y - 1, pz).setType(y < 0 ? Material.DEEPSLATE_BRICKS : Material.DIRT, false);
                for (int dy = 0; dy < sy; dy++) world.getBlockAt(px, y + dy, pz).setType(Material.AIR, false);
            });
        }
    }
    private static void cleanContainers(World world, int x, int y, int z, Structure structure, Consumer<Runnable> edits) {
        for (int dx = 0; dx < structure.getSize().getBlockX(); dx++) for (int dz = 0; dz < structure.getSize().getBlockZ(); dz++) {
            int px = x + dx, pz = z + dz;
            edits.accept(() -> {
                for (int dy = 0; dy < structure.getSize().getBlockY(); dy++) {
                    var block = world.getBlockAt(px, y + dy, pz);
                    if (block.getState() instanceof Container) block.setType(Material.AIR, false);
                    if (block.getType() == Material.JIGSAW || block.getType() == Material.STRUCTURE_BLOCK || block.getType() == Material.STRUCTURE_VOID) block.setType(Material.AIR, false);
                    if (block.getType() == Material.COMMAND_BLOCK || block.getType() == Material.CHAIN_COMMAND_BLOCK || block.getType() == Material.REPEATING_COMMAND_BLOCK) block.setType(Material.AIR, false);
                    if (block.getType() == Material.GOLD_BLOCK || block.getType() == Material.DIAMOND_BLOCK || block.getType() == Material.EMERALD_BLOCK) block.setType(Material.DEEPSLATE_BRICKS, false);
                }
            });
        }
    }
    private static void reward(World world, int x, int y, int z, List<ItemStack> items) {
        world.getBlockAt(x, y - 1, z).setType(Material.DEEPSLATE_BRICKS, false);
        world.getBlockAt(x, y, z).setType(Material.CHEST, false);
        world.getBlockAt(x, y + 1, z).setType(Material.AIR, false);
        Chest chest = (Chest) world.getBlockAt(x, y, z).getState();
        chest.setLootTable(null); chest.getBlockInventory().clear();
        for (int i = 0; i < items.size(); i++) chest.getBlockInventory().setItem(i, items.get(i));
        chest.update(true, false);
    }
}

