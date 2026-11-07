package cn.infinite.warwall.listener;

import cn.infinite.warwall.WarwallPlugin;
import cn.infinite.warwall.team.TeamColor;
import org.bukkit.GameMode;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wither;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * 连接、凋零死亡、主动技能释放。
 */
public final class GameListener implements Listener {

    private final WarwallPlugin plugin;

    public GameListener(WarwallPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWitherDeath(EntityDeathEvent event) {
        // 死斗 Boss 的掉落单独处理，别混进凋零逻辑。
        if (event.getEntity() instanceof LivingEntity living && plugin.boss().isBoss(living)) {
            plugin.boss().onBossKilled(living);
            return;
        }
        if (!(event.getEntity() instanceof Wither wither)) {
            return;
        }
        TeamColor team = plugin.withers().teamOf(wither);
        if (team == null) {
            return;
        }
        // 凋零不掉落任何东西，否则整个背包会撒在基座上。
        event.getDrops().clear();
        event.setDroppedExp(0);
        plugin.ui().broadcast("&c" + team.coloredName() + " 的凋零已被摧毁！");
        plugin.ui().broadcast("&7该队死亡后不再复活。");
        for (Player player : plugin.teams().onlineOf(team)) {
            player.sendMessage(plugin.config().prefix() + "§c你们的凋零已被摧毁，死亡后将无法复活。");
        }
        plugin.ui().updateAll();
    }

    /** 右键技能物品：能量满则释放。 */
    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_AIR
                && event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        if (!plugin.teams().hasTeam(player) || !plugin.controller().isRunning()) {
            return;
        }
        // 自定义物品的右键使用优先于职业技能物品。
        cn.infinite.warwall.item.CustomItems.ItemType custom =
                plugin.items().typeOf(event.getItem());
        if (custom != null && custom.kind() == cn.infinite.warwall.item.CustomItems.Kind.USE) {
            event.setCancelled(true);
            if (plugin.items().onUse(player, custom)) {
                event.getItem().setAmount(event.getItem().getAmount() - 1);
                player.updateInventory();
            }
            return;
        }
        if (!plugin.kits().isAbilityItem(event.getItem())) {
            return;
        }
        event.setCancelled(true);
        plugin.kits().activateAbility(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!plugin.controller().isRunning() || !plugin.teams().hasTeam(player)) {
            return;
        }
        // 局在跑且该玩家有队伍：放回场上并重发装备。
        plugin.ui().attachBossBars(player);
        plugin.teams().applyScoreboard(player);
        plugin.teams().markAlive(player);
        player.setGameMode(GameMode.SURVIVAL);
        plugin.kits().giveKit(player, plugin.teams().teamOf(player));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        plugin.ui().detachBossBars(player);
        plugin.ui().forget(player.getUniqueId());
        plugin.kits().forget(player.getUniqueId());
        // 掉线按死亡处理：不判击杀、不掉落，但存活计数要减。
        if (plugin.controller().isRunning() && plugin.teams().hasTeam(player)) {
            plugin.teams().markDead(player);
            plugin.ui().updateAll();
        }
    }
}
