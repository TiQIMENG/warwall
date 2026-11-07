package cn.infinite.warwall.nexus;

import cn.infinite.warwall.WarwallPlugin;
import cn.infinite.warwall.arena.Box;
import cn.infinite.warwall.arena.WarwallArena;
import cn.infinite.warwall.team.TeamColor;
import java.util.EnumMap;
import java.util.Map;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * 每队一座核心（Nexus），融合自「核心战争」。
 *
 * <p>核心＝队伍的「阵地」：决定在哪复活 + 提供团队增益。伤害方式是
 * **在核心区域里放置或破坏方块**，每个方块扣固定血量（见 docs/DESIGN-FUSION.md §4）。
 *
 * <p>参考实现：AnniTest 的 {@code nexus/NexusManager.java:23}（默认 75 HP）与
 * {@code nexus/NexusListener.java:61/113}（放/破方块扣血）、{@code :135}（前期无敌）。
 */
public final class NexusManager {

    private final WarwallPlugin plugin;
    private final Map<TeamColor, Integer> health = new EnumMap<>(TeamColor.class);
    private final Map<TeamColor, Box> regions = new EnumMap<>(TeamColor.class);
    private boolean enabled;
    private boolean attackable;

    public NexusManager(WarwallPlugin plugin) {
        this.plugin = plugin;
    }

    /** 开局初始化：读每队的核心区域并满血。 */
    public void init(WarwallArena arena) {
        reset();
        enabled = plugin.config().nexusEnabled() && arena != null;
        if (!enabled) {
            return;
        }
        int maxHealth = plugin.config().nexusMaxHealth();
        for (TeamColor team : TeamColor.values()) {
            WarwallArena.TeamSetup setup = arena.team(team.id());
            if (setup == null || setup.nexus() == null) {
                continue;
            }
            regions.put(team, setup.nexus());
            health.put(team, maxHealth);
        }
        plugin.getLogger().info("核心已初始化：" + regions.size() + " 座，每座 " + maxHealth + " 点");
    }

    public void reset() {
        health.clear();
        regions.clear();
        attackable = false;
    }

    public boolean enabled() {
        return enabled;
    }

    public void setAttackable(boolean value) {
        this.attackable = value;
    }

    public boolean isAttackable() {
        return attackable;
    }

    public Box regionOf(TeamColor team) {
        return regions.get(team);
    }

    public int healthOf(TeamColor team) {
        return health.getOrDefault(team, 0);
    }

    public boolean isAlive(TeamColor team) {
        if (!enabled) {
            return false;
        }
        Integer value = health.get(team);
        return value != null && value > 0;
    }

    /** 该坐标落在哪一队的核心区域里；没有则返回 null。 */
    public TeamColor teamOwning(String world, int x, int y, int z) {
        if (!enabled) {
            return null;
        }
        for (Map.Entry<TeamColor, Box> entry : regions.entrySet()) {
            Box box = entry.getValue();
            if (box.world().equalsIgnoreCase(world) && box.contains(x, y, z)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * 对某队核心造成伤害。
     *
     * @param team   被打的队伍
     * @param amount 伤害值（通常 1）
     * @param source 造成伤害的玩家，用于播报与归属；可为 null（管理员调试）
     * @return 本次是否真的扣了血
     */
    public boolean damage(TeamColor team, int amount, Player source) {
        if (!enabled || !attackable || amount <= 0) {
            return false;
        }
        Integer current = health.get(team);
        if (current == null || current <= 0) {
            return false;
        }
        int next = Math.max(0, current - amount);
        health.put(team, next);
        if (next == 0) {
            onDestroyed(team, source);
        }
        return true;
    }

    private void onDestroyed(TeamColor team, Player source) {
        String who = source == null ? "未知来源" : source.getName();
        plugin.ui().broadcast("&c" + team.coloredName() + " 的核心已被摧毁！"
                + (source == null ? "" : "（最后一击：" + who + "）"));
        plugin.ui().broadcast("&7该队失去团队增益，复活点变为核心残骸附近。");
        for (Player player : plugin.teams().onlineOf(team)) {
            player.sendMessage(plugin.config().prefix() + "§c你们的核心已被摧毁，增益消失，复活点失效。");
            player.removePotionEffect(PotionEffectType.HASTE);
        }
    }

    /** 核心存活时给全队补一次团队增益，由 MatchController 定时调用。 */
    public void refreshTeamBuff() {
        if (!enabled || !attackable) {
            return;
        }
        PotionEffectType type = PotionEffectType.getByName(plugin.config().nexusTeamBuffName());
        if (type == null) {
            return;
        }
        int amplifier = plugin.config().nexusTeamBuffAmplifier();
        int ticks = plugin.config().nexusTeamBuffRefreshTicks() + 40;
        for (TeamColor team : TeamColor.values()) {
            if (!isAlive(team)) {
                continue;
            }
            for (Player player : plugin.teams().onlineOf(team)) {
                player.addPotionEffect(new PotionEffect(type, ticks, amplifier, true, false, false));
            }
        }
    }

    /** 该队复活点是否还能用（核心存活即视为有效）。 */
    public boolean canUseSpawnPoints(TeamColor team) {
        return !enabled || isAlive(team);
    }

    /** 核心被毁后的复活点：在残骸周围随机散布，避免所有人在同一点被围杀。 */
    public org.bukkit.Location scatterRespawn(TeamColor team, String worldName, org.bukkit.Location fallback) {
        Box region = regions.get(team);
        if (region == null || fallback == null) {
            return fallback;
        }
        org.bukkit.World world = fallback.getWorld();
        double radius = plugin.config().nexusRespawnScatterRadius();
        double angle = Math.random() * Math.PI * 2;
        double distance = Math.random() * radius;
        double x = region.centerX() + Math.cos(angle) * distance;
        double z = region.centerZ() + Math.sin(angle) * distance;
        return new org.bukkit.Location(world, x, region.maxY() + 1.0, z,
                fallback.getYaw(), fallback.getPitch());
    }
}
