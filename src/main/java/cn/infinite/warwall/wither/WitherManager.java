package cn.infinite.warwall.wither;

import cn.infinite.warwall.WarwallPlugin;
import cn.infinite.warwall.arena.WarwallArena;
import cn.infinite.warwall.team.TeamColor;
import java.util.EnumMap;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Boss;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wither;
import org.bukkit.entity.WitherSkull;
import org.bukkit.util.Vector;

/**
 * 每队一座凋零。
 *
 * <p>凋零＝队伍的「复活许可」：活着 → 死亡可复活；被摧毁 → 死亡转旁观。
 *
 * <p>实现要点：
 * <ul>
 *   <li>1.21.11 的 {@code Mob} 只有 {@code setAware(boolean)}，没有 {@code setAI}；用 setAware(false) 钉住基座。</li>
 *   <li>原版凋零自带血条会和我们自建的四人血条打架，用 {@code Boss#getBossBar().removeAll()} 关掉。</li>
 *   <li>不依赖原版 AI 开火：自己 spawn {@code WitherSkull} 并设置 shooter，行为可控。</li>
 * </ul>
 */
public final class WitherManager {

    private final WarwallPlugin plugin;
    private final Map<TeamColor, Wither> withers = new EnumMap<>(TeamColor.class);
    private boolean attackable;
    private int tickCounter;

    public WitherManager(WarwallPlugin plugin) {
        this.plugin = plugin;
    }

    /** 按竞技场配置生成四座凋零。 */
    public void spawnAll(WarwallArena arena) {
        despawnAll();
        if (arena == null || !arena.worldLoaded()) {
            plugin.getLogger().warning("竞技场世界未加载，无法生成凋零");
            return;
        }
        for (TeamColor team : TeamColor.values()) {
            WarwallArena.TeamSetup setup = arena.team(team.id());
            if (setup == null || setup.wither() == null) {
                plugin.getLogger().warning(team.id() + " 缺少凋零基座，跳过生成");
                continue;
            }
            Location location = setup.wither().toLocation(arena.worldName());
            if (location == null) {
                continue;
            }
            Wither wither = location.getWorld().spawn(location, Wither.class,
                    org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM);
            wither.setAware(false);
            wither.setPersistent(true);
            wither.setCustomName(team.coloredName() + " 的凋零");
            wither.setCustomNameVisible(true);
            AttributeInstance maxHealth = wither.getAttribute(Attribute.MAX_HEALTH);
            if (maxHealth != null) {
                maxHealth.setBaseValue(plugin.config().witherMaxHealth());
            }
            wither.setHealth(wither.getMaxHealth());
            wither.setInvulnerable(!attackable);
            // 关掉原版凋零血条，避免与自建的四队血条重复显示。
            if (wither instanceof Boss boss) {
                boss.getBossBar().removeAll();
            }
            withers.put(team, wither);
        }
        plugin.getLogger().info("已生成 " + withers.size() + " 座凋零，每座 "
                + (int) plugin.config().witherMaxHealth() + " 点生命");
    }

    public void despawnAll() {
        for (Wither wither : withers.values()) {
            if (wither != null && !wither.isDead()) {
                wither.remove();
            }
        }
        withers.clear();
    }

    /** 是否允许被攻击；准备期恒为 false。 */
    public void setAttackable(boolean value) {
        this.attackable = value;
        for (Wither wither : withers.values()) {
            if (wither != null && !wither.isDead()) {
                wither.setInvulnerable(!value);
            }
        }
    }

    public boolean isAttackable() {
        return attackable;
    }

    public boolean isAlive(TeamColor team) {
        Wither wither = withers.get(team);
        return wither != null && !wither.isDead() && wither.isValid();
    }

    public int healthOf(TeamColor team) {
        Wither wither = withers.get(team);
        if (wither == null || wither.isDead()) {
            return 0;
        }
        return (int) Math.ceil(wither.getHealth());
    }

    public Wither witherOf(TeamColor team) {
        return withers.get(team);
    }

    /** 直接摧毁某队凋零（管理员命令与调试用）。 */
    public void kill(TeamColor team) {
        Wither wither = withers.get(team);
        if (wither != null && !wither.isDead()) {
            wither.setHealth(0.0);
        }
    }

    /** 由凋零死亡事件回调，交给 MatchController 处理复活规则切换。 */
    public TeamColor teamOf(Wither wither) {
        for (Map.Entry<TeamColor, Wither> entry : withers.entrySet()) {
            if (entry.getValue() != null && entry.getValue().getUniqueId().equals(wither.getUniqueId())) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * 每秒调度一次：让存活的凋零向附近敌人发射凋零骷髅。
     *
     * <p>自己 spawn 投射物而不是用 {@code launchProjectile}，因为后者对凋零并不保证成功，
     * 而且无法控制伤害与飞行方向。
     */
    public void tick() {
        tickCounter++;
        if (tickCounter % 20 != 0) {
            return;
        }
        int interval = plugin.config().witherSkullIntervalSeconds();
        double aggroRadius = plugin.config().witherAggroRadius();
        for (Map.Entry<TeamColor, Wither> entry : withers.entrySet()) {
            Wither wither = entry.getValue();
            if (wither == null || wither.isDead()) {
                continue;
            }
            int period = isEnraged(entry.getKey())
                    ? plugin.config().witherEnrageSkullIntervalSeconds()
                    : interval;
            if (tickCounter % Math.max(20, period * 20) != 0) {
                continue;
            }
            Player target = nearestEnemy(entry.getKey(), wither.getLocation(), aggroRadius);
            if (target == null) {
                continue;
            }
            Vector direction = target.getEyeLocation().toVector()
                    .subtract(wither.getEyeLocation().toVector())
                    .normalize()
                    .multiply(1.2);
            WitherSkull skull = wither.getWorld().spawn(wither.getEyeLocation(), WitherSkull.class,
                    org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM);
            skull.setShooter(wither);
            skull.setDirection(direction);
        }
    }

    private boolean isEnraged(TeamColor team) {
        Wither wither = withers.get(team);
        if (wither == null) {
            return false;
        }
        double percent = plugin.config().witherEnragePercent() / 100.0;
        return wither.getHealth() <= wither.getMaxHealth() * percent;
    }

    private Player nearestEnemy(TeamColor team, Location from, double radius) {
        Player best = null;
        double bestDistance = radius * radius;
        for (Player player : from.getWorld().getPlayers()) {
            TeamColor playerTeam = plugin.teams().teamOf(player);
            if (playerTeam == null || playerTeam == team || player.isDead()) {
                continue;
            }
            double distance = player.getLocation().distanceSquared(from);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = player;
            }
        }
        return best;
    }
}
