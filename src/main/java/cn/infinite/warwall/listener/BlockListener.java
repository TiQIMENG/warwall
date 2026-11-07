package cn.infinite.warwall.listener;

import cn.infinite.warwall.WarwallPlugin;
import cn.infinite.warwall.arena.WarwallArena;
import cn.infinite.warwall.core.PhaseRules;
import cn.infinite.warwall.team.TeamColor;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

/**
 * 方块保护与核心伤害。
 *
 * <p>判定顺序固定为：**阶段是否允许编辑 → 墙区域 → unbreakable → mining-zones → no-build-zones**，
 * 顺序反过来会让「墙内恰好有矿区」之类的配置互相打脸。
 *
 * <p>核心伤害就在这个监听器里：敌队在核心区域里放置或破坏方块各扣固定血量
 * （参考 AnniTest 的 {@code NexusListener#onBlockPlace/onBlockBreak}）。
 */
public final class BlockListener implements Listener {

    private final WarwallPlugin plugin;

    public BlockListener(WarwallPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Player player = event.getPlayer();
        if (denyEdit(event, block, player)) {
            return;
        }
        damageNexus(block, player, plugin.config().nexusBlocksBrokenDamage());
        plugin.kits().onBlockBroken(player);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        Player player = event.getPlayer();
        if (denyEdit(event, block, player)) {
            return;
        }
        damageNexus(block, player, plugin.config().nexusBlocksPlacedDamage());
        plugin.kits().onBlockPlaced(player);
    }

    /** 返回 true 表示已取消，调用方不要再继续处理。 */
    private boolean denyEdit(org.bukkit.event.Cancellable event, Block block, Player player) {
        PhaseRules rules = plugin.controller().rules();
        WarwallArena arena = plugin.controller().arena();

        if (!rules.blocksEditable()) {
            event.setCancelled(true);
            return true;
        }
        if (arena == null) {
            event.setCancelled(true);
            return true;
        }
        // 1) 准备期的墙不可破坏
        if (rules.wallSolid() && plugin.walls().isWallBlock(block)) {
            event.setCancelled(true);
            player.sendMessage(plugin.config().prefix() + "§c天空之壁还没有倒塌，现在挖不动。");
            return true;
        }
        String world = block.getWorld().getName();
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();
        // 2) 永远不可破坏的区域（凋零房 / 核心房）
        if (arena.isUnbreakable(world, x, y, z)) {
            event.setCancelled(true);
            return true;
        }
        // 3) 只允许在矿区挖
        boolean breaking = event instanceof BlockBreakEvent;
        if (breaking && !arena.isMineable(world, x, y, z)) {
            event.setCancelled(true);
            player.sendMessage(plugin.config().prefix() + "§c这里不在允许开采的区域内。");
            return true;
        }
        // 4) 禁止放置
        if (!breaking && arena.isNoBuild(world, x, y, z)) {
            event.setCancelled(true);
            player.sendMessage(plugin.config().prefix() + "§c这里不允许放置方块。");
            return true;
        }
        return false;
    }

    private void damageNexus(Block block, Player player, int amount) {
        if (!plugin.nexus().enabled() || !plugin.nexus().isAttackable() || amount <= 0) {
            return;
        }
        String world = block.getWorld().getName();
        TeamColor owner = plugin.nexus().teamOwning(world, block.getX(), block.getY(), block.getZ());
        if (owner == null) {
            return;
        }
        TeamColor attackerTeam = plugin.teams().teamOf(player);
        // 自己在自己核心里摆方块是加固，不算伤害。
        if (attackerTeam == owner) {
            return;
        }
        if (plugin.nexus().damage(owner, amount, player)) {
            player.sendMessage(plugin.config().prefix() + "§e已对 " + owner.coloredName()
                    + " 的核心造成 " + amount + " 点伤害，剩余 §f"
                    + plugin.nexus().healthOf(owner));
        }
    }
}
