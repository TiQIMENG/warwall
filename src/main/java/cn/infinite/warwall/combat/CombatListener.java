package cn.infinite.warwall.combat;

import cn.infinite.warwall.WarwallPlugin;
import cn.infinite.warwall.core.MatchState;
import cn.infinite.warwall.team.TeamColor;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;

/**
 * UHC 战斗规则：友伤、凋零受击、伤害系数、能量、物品黑名单、攻击冷却防绕过。
 *
 * <p>1.21.11 的剑是 1.6 击/秒，攻击冷却天然限制了 DPS；这里**不改攻速**，
 * 只在切手/换栏时 {@code resetCooldown()}，防止靠快速切剑绕过冷却。
 */
public final class CombatListener implements Listener {

    private final WarwallPlugin plugin;

    public CombatListener(WarwallPlugin plugin) {
        this.plugin = plugin;
    }

    /** 玩家之间的伤害：友伤、阶段、伤害系数。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Player attacker = resolveAttacker(event);
        if (!(event.getEntity() instanceof Player victim)) {
            handleWitherDamage(event, attacker);
            return;
        }
        TeamColor victimTeam = plugin.teams().teamOf(victim);
        TeamColor attackerTeam = attacker == null ? null : plugin.teams().teamOf(attacker);
        if (victimTeam == null || attackerTeam == null) {
            return;
        }

        if (attackerTeam == victimTeam) {
            if (!plugin.config().friendlyFire()) {
                event.setCancelled(true);
            }
            return;
        }
        if (!plugin.controller().rules().pvpAllowed()) {
            event.setCancelled(true);
            return;
        }

        double multiplier = event.getDamager() instanceof Projectile
                ? plugin.config().projectileDamageMultiplier()
                : plugin.config().meleeDamageMultiplier();
        event.setDamage(event.getDamage() * multiplier);

        plugin.stats().onDamage(attacker, event.getDamage());
        recordDamager(victim, attacker);
        // 真实命中才结算：无伤帧未过或被取消都不算能量与被动。
        if (victim.getNoDamageTicks() < victim.getMaximumNoDamageTicks() / 2) {
            plugin.kits().addEnergyForHit(attacker);
            plugin.kits().onHit(attacker, victim);
            plugin.kits().onDamaged(victim, attacker);
            // 自定义武器的命中效果（破舰者真伤、锋针减速、猎人护身符叠层）
            plugin.items().onWeaponHit(attacker, victim, attacker.getInventory().getItemInMainHand());
            if (event.getDamager() instanceof Projectile) {
                plugin.kits().onProjectileHit(attacker);
            }
        }
    }

    private void handleWitherDamage(EntityDamageByEntityEvent event, Player attacker) {
        if (!(event.getEntity() instanceof org.bukkit.entity.Wither wither)) {
            return;
        }
        TeamColor ownerTeam = plugin.withers().teamOf(wither);
        if (ownerTeam == null) {
            return;
        }
        TeamColor attackerTeam = attacker == null ? null : plugin.teams().teamOf(attacker);
        // 自己人打自己凋零：取消并提示；非战斗阶段也取消。
        if (attackerTeam == ownerTeam || attackerTeam == null
                || !plugin.controller().rules().witherAttackable()) {
            event.setCancelled(true);
            return;
        }
        if (attacker != null) {
            plugin.stats().onDamage(attacker, event.getDamage());
        }
        event.setDamage(event.getDamage() * plugin.config().damageToWitherMultiplier());
    }

    private Player resolveAttacker(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) {
            return player;
        }
        if (event.getDamager() instanceof Projectile projectile
                && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        return null;
    }

    private void recordDamager(Player victim, Player attacker) {
        plugin.combat().recordDamager(victim.getUniqueId(), attacker.getUniqueId());
    }

    /** 金苹果限频与黑名单药水。 */
    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        ItemStack item = event.getItem();
        if (item.getType() == Material.GOLDEN_APPLE) {
            int cooldown = plugin.config().gappleCooldownSeconds();
            if (cooldown > 0) {
                if (event.getPlayer().hasCooldown(Material.GOLDEN_APPLE)) {
                    event.setCancelled(true);
                    event.getPlayer().sendMessage(plugin.config().prefix() + "§c金苹果还在冷却。");
                    return;
                }
                event.getPlayer().setCooldown(Material.GOLDEN_APPLE, cooldown * 20);
            }
        }
    }

    /** 黑名单物品：手持即拦下并提示。 */
    @EventHandler(ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent event) {
        ItemStack item = event.getPlayer().getInventory().getItem(event.getNewSlot());
        if (item == null) {
            return;
        }
        if (plugin.config().itemBlacklist().contains(item.getType())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(plugin.config().prefix()
                    + "§c本局禁用 " + item.getType().name() + "。");
        }
    }

    /** 切副手也算一次「换武器」，重置攻击冷却，防止绕过 1.9+ 冷却。 */
    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (plugin.config().resetCooldownOnSwap()) {
            event.getPlayer().resetCooldown();
        }
    }

    /** 玩家死亡时清掉记录，避免内存泄漏。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeathCleanup(PlayerDeathEvent event) {
        plugin.combat().forget(event.getEntity().getUniqueId());
    }

    /** 凋零/核心相关方块操作由 BlockListener 处理，这里只阻断取消事件里的非法交互。 */
    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        ItemStack current = event.getCurrentItem();
        if (current != null && plugin.config().itemBlacklist().contains(current.getType())) {
            event.setCancelled(true);
        }
    }

    /** 是否处于可打架阶段。 */
    public boolean isCombatPhase() {
        return plugin.controller().state().isCombat();
    }

    /** 供 UI 判断用的阶段名。 */
    public MatchState currentState() {
        return plugin.controller().state();
    }
}
