package cn.infinite.warwall.building;

import cn.infinite.warwall.WarwallPlugin;
import cn.infinite.warwall.team.TeamColor;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * 建筑系统：可放置的「光环建筑」。
 *
 * <p>取自 wallwar 数据包 {@code build/*} 的思路——建筑不是装饰，而是**范围效果源**：
 * 己方站在范围内获得增益，敌方进入范围吃减益；敌人打掉核心方块就能拆掉它。
 *
 * <p>与数据包不同之处：数据包用 item_frame + 预置 structure 文件来做，这里改成
 * 程序化生成核心方块 + 登记光环，不需要任何 .nbt 素材，也不需要资源包。
 */
public final class BuildingManager {

    /** 建筑类型。{@code allies=true} 表示对己方生效，否则对敌方生效。 */
    public enum BuildingType {
        // ===== 基础四种（对齐数据包 electric / farm / flag / court）=====
        BATTLE_COURT("战斗庭院", Material.LODESTONE, PotionEffectType.STRENGTH, 8, true, 0),
        FARM("农田", Material.HAY_BLOCK, PotionEffectType.REGENERATION, 8, true, 0),
        WAR_BANNER("号令之旗", Material.WHITE_BANNER, PotionEffectType.SPEED, 10, true, 0),
        TESLA_TOWER("特斯拉塔", Material.LIGHTNING_ROD, PotionEffectType.SLOWNESS, 6, false, 0),

        // ===== 对齐数据包 tower / court =====
        TOWER("瞭望塔", Material.STONE_BRICK_WALL, PotionEffectType.RESISTANCE, 12, true, 0),
        GRAND_COURT("方形剧场", Material.CUT_COPPER, PotionEffectType.REGENERATION, 12, true, 0),

        // ===== 对齐数据包 wither =====
        WITHER_TOWER("凋零塔", Material.WITHER_SKELETON_SKULL, PotionEffectType.WITHER, 8, false, 0),

        // ===== 对齐数据包 respawn =====
        RESPAWN_SHRINE("复活祭坛", Material.RESPAWN_ANCHOR, PotionEffectType.ABSORPTION, 6, true, 0),

        // ===== 对齐数据包 spawner =====
        SPAWNER_ALTAR("刷怪祭坛", Material.SPAWNER, PotionEffectType.STRENGTH, 10, true, 1),

        // ===== 对齐数据包 trans / trans2 =====
        TRANS_PAD("传送阵", Material.ENDER_EYE, PotionEffectType.SPEED, 14, true, 1),

        // ===== 对齐数据包 sand =====
        SAND_WALL("沙垒", Material.SANDSTONE, PotionEffectType.MINING_FATIGUE, 8, false, 0),

        // ===== 对齐数据包 player（私有建筑）=====
        CLOCK_TOWER("时钟塔", Material.CLOCK, PotionEffectType.HASTE, 10, true, 1);

        private final String chineseName;
        private final Material core;
        private final PotionEffectType effect;
        private final int radius;
        private final boolean allies;
        private final int amplifier;

        BuildingType(String chineseName, Material core, PotionEffectType effect,
                     int radius, boolean allies, int amplifier) {
            this.chineseName = chineseName;
            this.core = core;
            this.effect = effect;
            this.radius = radius;
            this.allies = allies;
            this.amplifier = amplifier;
        }

        public String chineseName() {
            return chineseName;
        }

        public Material core() {
            return core;
        }

        public PotionEffectType effect() {
            return effect;
        }

        public int radius() {
            return radius;
        }

        public boolean allies() {
            return allies;
        }

        public int amplifier() {
            return amplifier;
        }

        public static BuildingType parse(String raw) {
            if (raw == null) {
                return null;
            }
            String key = raw.trim().toUpperCase(java.util.Locale.ROOT);
            for (BuildingType type : values()) {
                if (type.name().equals(key) || type.chineseName.equals(raw.trim())) {
                    return type;
                }
            }
            return null;
        }
    }

    /** 一个已放置的建筑。 */
    public record Building(TeamColor owner, BuildingType type, Location core) {
    }

    private final WarwallPlugin plugin;
    private final List<Building> buildings = new ArrayList<>();

    public BuildingManager(WarwallPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 放下一个建筑。
     *
     * @return 成功返回 true；距离太近或场地不合适返回 false
     */
    public boolean place(Player placer, BuildingType type) {
        TeamColor team = plugin.teams().teamOf(placer);
        if (team == null) {
            return false;
        }
        Location location = placer.getLocation().toBlockLocation();
        for (Building existing : buildings) {
            if (existing.core().getWorld().equals(location.getWorld())
                    && existing.core().distanceSquared(location) < 36) {
                placer.sendMessage(plugin.config().prefix() + "§c离已有建筑太近了（至少 6 格）。");
                return false;
            }
        }
        Block core = location.getBlock();
        core.setType(type.core(), false);
        buildings.add(new Building(team, type, core.getLocation()));
        plugin.ui().broadcast("&7" + team.coloredName() + " 放下了 &f" + type.chineseName()
                + "&7（范围 " + type.radius() + " 格）。");
        return true;
    }

    /** 每秒结算一次光环；核心方块被换掉就等于被拆除。 */
    public void tick() {
        if (buildings.isEmpty()) {
            return;
        }
        Iterator<Building> iterator = buildings.iterator();
        while (iterator.hasNext()) {
            Building building = iterator.next();
            Block core = building.core().getBlock();
            if (core.getType() != building.type().core()) {
                iterator.remove();
                plugin.ui().broadcast("&c" + building.owner().coloredName() + " 的 "
                        + building.type().chineseName() + " 被摧毁了。");
                continue;
            }
            applyAura(building);
        }
    }

    private void applyAura(Building building) {
        double radiusSquared = (double) building.type().radius() * building.type().radius();
        for (Player player : building.core().getWorld().getPlayers()) {
            TeamColor playerTeam = plugin.teams().teamOf(player);
            if (playerTeam == null || player.isDead()) {
                continue;
            }
            if (player.getLocation().distanceSquared(building.core()) > radiusSquared) {
                continue;
            }
            boolean sameTeam = playerTeam == building.owner();
            if (sameTeam != building.type().allies()) {
                continue;
            }
            player.addPotionEffect(new PotionEffect(
                    building.type().effect(), 60, building.type().amplifier(), true, false, false));
        }
    }

    /** 收局清理：只注销登记，不改地形（地形由地图自己负责）。 */
    public void clear() {
        buildings.clear();
    }

    public int count() {
        return buildings.size();
    }

    public List<Building> all() {
        return List.copyOf(buildings);
    }
}
