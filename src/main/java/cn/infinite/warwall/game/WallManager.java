package cn.infinite.warwall.game;

import cn.infinite.warwall.WarwallPlugin;
import cn.infinite.warwall.arena.Box;
import cn.infinite.warwall.arena.WarwallArena;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

/**
 * 天空之壁的捕获、倒塌与还原。
 *
 * <p>数据包的做法是「准备期用基岩墙挡住，战斗开始时把墙换成可挖的矿物墙」。
 * 插件里没法预置基岩墙，所以改成：准备期记录玩家读到的墙方块快照，
 * 战斗开始时统一替换成矿物墙，收局时按快照还原。
 */
public final class WallManager {

    private final WarwallPlugin plugin;

    /** 快照：每个方块一份 BlockData，按捕获顺序保存。 */
    private final List<Snapshot> snapshot = new ArrayList<>();

    private record Snapshot(int x, int y, int z, BlockData data) {
    }

    private boolean captured;
    private boolean dropped;

    public WallManager(WarwallPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 进入准备期时捕获墙方块。
     *
     * @return true 表示捕获成功或墙本来就不需要处理
     */
    public boolean capture() {
        snapshot.clear();
        captured = false;
        dropped = false;
        WarwallArena arena = plugin.controller().arena();
        if (arena == null || !arena.worldLoaded()) {
            return false;
        }
        long max = plugin.config().wallMaxVolume();
        long total = 0;
        for (Box box : arena.walls()) {
            total += box.volume();
        }
        if (total > max) {
            plugin.getLogger().warning("墙区域总体积 " + total + " 超过上限 " + max + "，本次不捕获，墙不会被移除");
            return false;
        }
        World world = arena.worldName() == null ? null : org.bukkit.Bukkit.getWorld(arena.worldName());
        if (world == null) {
            return false;
        }
        for (Box box : arena.walls()) {
            if (!box.world().equalsIgnoreCase(world.getName())) {
                continue;
            }
            for (int x = box.minX(); x <= box.maxX(); x++) {
                for (int y = box.minY(); y <= box.maxY(); y++) {
                    for (int z = box.minZ(); z <= box.maxZ(); z++) {
                        Block block = world.getBlockAt(x, y, z);
                        if (block.getType().isAir()) {
                            continue;
                        }
                        snapshot.add(new Snapshot(x, y, z, block.getBlockData().clone()));
                    }
                }
            }
        }
        captured = !snapshot.isEmpty();
        plugin.getLogger().info("天空之壁已捕获：" + snapshot.size() + " 个方块");
        return true;
    }

    /**
     * 墙倒：把快照里的方块换成可挖的矿物墙，并播放崩塌音效。
     *
     * <p>按「石头为主 + 少量铁矿」的方式生成，保留一点挖矿收益，和技能手册里
     * 「基岩墙变矿物墙」的原意一致。
     */
    public void drop() {
        WarwallArena arena = plugin.controller().arena();
        if (arena == null || !captured) {
            plugin.getLogger().warning("墙未捕获，跳过倒塌");
            return;
        }
        World world = org.bukkit.Bukkit.getWorld(arena.worldName());
        if (world == null) {
            return;
        }
        java.util.Random random = new java.util.Random();
        for (Snapshot entry : snapshot) {
            Block block = world.getBlockAt(entry.x(), entry.y(), entry.z());
            Material material = random.nextInt(10) == 0 ? Material.IRON_ORE : Material.STONE;
            block.setType(material, false);
        }
        playDropSound(world, arena);
        captured = false;
        dropped = true;
        plugin.getLogger().info("天空之壁已倒塌，共 " + snapshot.size() + " 个方块转为矿物墙");
    }

    /** 分批还原墙，供收局与中止时调用。 */
    public void restore() {
        if (snapshot.isEmpty()) {
            return;
        }
        WarwallArena arena = plugin.controller().arena();
        World world = arena == null ? null : org.bukkit.Bukkit.getWorld(arena.worldName());
        if (world == null) {
            snapshot.clear();
            return;
        }
        int processed = 0;
        int perTick = plugin.config().wallRestorePerTick();
        for (Snapshot entry : new ArrayList<>(snapshot)) {
            if (processed >= perTick) {
                break;
            }
            world.getBlockAt(entry.x(), entry.y(), entry.z()).setBlockData(entry.data(), false);
            snapshot.remove(entry);
            processed++;
        }
        if (!snapshot.isEmpty()) {
            org.bukkit.Bukkit.getScheduler().runTask(plugin, this::restore);
        }
    }

    /** 该坐标是否属于墙区域（准备期用来阻止破坏）。 */
    public boolean isWallBlock(Block block) {
        WarwallArena arena = plugin.controller().arena();
        if (arena == null || block == null) {
            return false;
        }
        String world = block.getWorld().getName();
        for (Box box : arena.walls()) {
            if (box.world().equalsIgnoreCase(world)
                    && box.contains(block.getX(), block.getY(), block.getZ())) {
                return true;
            }
        }
        return false;
    }

    public int capturedBlocks() {
        return snapshot.size();
    }

    /** 墙是否已经倒塌过（强制跳阶段时用来判断还要不要再倒一次）。 */
    public boolean isDropped() {
        return dropped;
    }

    private void playDropSound(World world, WarwallArena arena) {
        WarwallArena.SpawnPoint center = arena.center();
        if (center == null) {
            return;
        }
        org.bukkit.Location location = center.toLocation(world.getName());
        if (location == null) {
            return;
        }
        world.playSound(location, dropSound(), 2.0f, 0.5f);
    }

    /**
     * 读 {@code wall.drop-sound}。
     *
     * <p>1.21.11 的 {@link Sound} 是注册表接口常量而不是枚举，没有可靠的「按字符串取名」入口，
     * 所以这里只认几个常见音效名，其余一律退回默认值，绝不因为写错配置就抛异常。
     */
    private Sound dropSound() {
        String raw = plugin.getConfig().getString("wall.drop-sound", "BLOCK_STONE_BREAK");
        if (raw == null || raw.isBlank()) {
            return Sound.BLOCK_STONE_BREAK;
        }
        return switch (raw.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "BLOCK_STONE_BREAK" -> Sound.BLOCK_STONE_BREAK;
            case "ENTITY_GENERIC_EXPLODE" -> Sound.ENTITY_GENERIC_EXPLODE;
            case "BLOCK_ANVIL_LAND" -> Sound.BLOCK_ANVIL_LAND;
            case "ENTITY_ENDER_DRAGON_GROWL" -> Sound.ENTITY_ENDER_DRAGON_GROWL;
            case "ENTITY_WITHER_SPAWN" -> Sound.ENTITY_WITHER_SPAWN;
            default -> Sound.BLOCK_STONE_BREAK;
        };
    }
}
