package cn.infinite.warwall.boss;

import cn.infinite.warwall.WarwallPlugin;
import cn.infinite.warwall.arena.WarwallArena;
import cn.infinite.warwall.team.TeamColor;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * 世界 Boss「深渊守卫」——从 wallwar 数据包的 Boss 模式里取的一个最小切片。
 *
 * <p>触发时机：死斗的 {@code EDEN_APPLE} 阶段。它同时攻击所有队伍，逼玩家在抢伊甸之果
 * 和打 Boss 之间做选择；击杀后掉伊甸之果与金币。
 *
 * <p>刻意不做的部分（数据包里有但我们不搬）：多阶段 Boss 技能、独立 Boss 场地、
 * 专属掉落表。那些需要一整套物品与进度系统，本期只保留「死斗中央出现强敌」这个体验点。
 */
public final class BossManager {

    private final WarwallPlugin plugin;
    private LivingEntity boss;
    private String bossName = "§4§l深渊守卫";

    public BossManager(WarwallPlugin plugin) {
        this.plugin = plugin;
    }

    /** Boss 形态：随死斗阶段升级。 */
    public enum Form {
        /** 战斗期最后阶段的守卫：不掉落，只打断刷矿节奏。 */
        GUARDIAN("守卫者", 80.0, 5.0, null),
        /** 伊甸之果阶段的深渊守卫。 */
        ABYSS("深渊守卫", 200.0, 8.0, null),
        /** 最终死斗的觉醒形态：附带凋零 II 光环。 */
        AWAKENED("深渊守卫·觉醒", 400.0, 12.0, org.bukkit.potion.PotionEffectType.WITHER);

        private final String displayName;
        private final double health;
        private final double damage;
        private final org.bukkit.potion.PotionEffectType aura;

        Form(String displayName, double health, double damage,
             org.bukkit.potion.PotionEffectType aura) {
            this.displayName = displayName;
            this.health = health;
            this.damage = damage;
            this.aura = aura;
        }
    }

    private Form form = Form.ABYSS;

    /** 按阶段召唤对应形态；已经有一只时先清掉再换。 */
    public void spawn(WarwallArena arena, Form form) {
        despawn();
        this.form = form;
        spawn(arena);
    }

    /** 在场地中央召唤 Boss。 */
    public void spawn(WarwallArena arena) {
        if (boss != null && !boss.isDead()) {
            return;
        }
        if (arena == null || arena.center() == null) {
            return;
        }
        Location location = arena.center().toLocation(arena.worldName());
        if (location == null) {
            return;
        }
        Location spawn = location.clone().add(0, 1, 0);
        boss = (LivingEntity) spawn.getWorld().spawnEntity(spawn, EntityType.RAVAGER);
        boss.customName(Component.text("§4§l" + form.displayName));
        boss.setCustomNameVisible(true);
        boss.setPersistent(false);

        AttributeInstance health = boss.getAttribute(Attribute.MAX_HEALTH);
        if (health != null) {
            health.setBaseValue(form.health);
        }
        boss.setHealth(boss.getMaxHealth());
        AttributeInstance attack = boss.getAttribute(Attribute.ATTACK_DAMAGE);
        if (attack != null) {
            attack.setBaseValue(form.damage);
        }
        plugin.ui().broadcast("&4" + form.displayName + " 降临场地中央。");
    }

    /** 由死亡监听器调用；确认是 Boss 才处理。 */
    public boolean isBoss(LivingEntity entity) {
        return boss != null && boss.getUniqueId().equals(entity.getUniqueId());
    }

    /** Boss 死亡：掉伊甸之果、给击杀者金币。 */
    public void onBossKilled(LivingEntity entity) {
        if (!isBoss(entity)) {
            return;
        }
        Player killer = entity.getKiller();
        Location dropAt = entity.getLocation();
        for (int i = 0; i < plugin.config().bossEdenDrops(); i++) {
            dropAt.getWorld().dropItemNaturally(dropAt, edenApple());
        }
        if (killer != null) {
            plugin.economy().addCoins(killer, plugin.config().bossKillCoins());
            plugin.ui().broadcast("&6" + killer.getName() + " 击杀了深渊守卫，获得 "
                    + plugin.config().bossKillCoins() + " 金币。");
        } else {
            plugin.ui().broadcast("&6深渊守卫已被击杀。");
        }
        boss = null;
    }

    /** 当前 Boss 是否存活。 */
    public boolean isAlive() {
        return boss != null && !boss.isDead() && boss.isValid();
    }

    public LivingEntity current() {
        return boss;
    }

    /** 收局清理。 */
    public void despawn() {
        if (boss != null && !boss.isDead()) {
            boss.remove();
        }
        boss = null;
    }

    /** 让 Boss 主动追最近的敌对玩家，避免它站在中央发呆。 */
    public void tick() {
        if (!isAlive()) {
            return;
        }
        Player nearest = null;
        double best = Double.MAX_VALUE;
        for (Player player : boss.getWorld().getPlayers()) {
            TeamColor team = plugin.teams().teamOf(player);
            if (team == null || player.isDead() || player.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
                continue;
            }
            double distance = player.getLocation().distanceSquared(boss.getLocation());
            if (distance < best) {
                best = distance;
                nearest = player;
            }
        }
        if (nearest != null && boss instanceof org.bukkit.entity.Mob mob) {
            mob.setTarget(nearest);
        }
    }

    private ItemStack edenApple() {
        ItemStack apple = new ItemStack(Material.ENCHANTED_GOLDEN_APPLE, 1);
        var meta = apple.getItemMeta();
        if (meta != null) {
            meta.itemName(Component.text("§6§l伊甸之果"));
            apple.setItemMeta(meta);
        }
        return apple;
    }
}
