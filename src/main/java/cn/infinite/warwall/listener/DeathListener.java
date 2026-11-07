package cn.infinite.warwall.listener;

import cn.infinite.warwall.WarwallPlugin;
import cn.infinite.warwall.core.PhaseRules;
import cn.infinite.warwall.team.TeamColor;
import java.util.List;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

/**
 * 死亡、掉落、击杀奖励与复活。
 *
 * <p>规则随阶段变化，全部读 {@link PhaseRules}：
 * 准备期不掉落；战斗期全掉，被玩家击杀额外掉头颅与金苹果；死斗期不再复活。
 */
public final class DeathListener implements Listener {

    private final WarwallPlugin plugin;

    public DeathListener(WarwallPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        TeamColor team = plugin.teams().teamOf(victim);
        if (team == null) {
            return;
        }
        PhaseRules rules = plugin.controller().rules();
        Player killer = victim.getKiller();

        plugin.teams().markDead(victim);
        plugin.stats().onKill(killer, victim);
        plugin.combat().awardAssists(victim);

        // 掉落策略
        List<ItemStack> drops = event.getDrops();
        if (!rules.dropItemsOnDeath()) {
            drops.clear();
            event.setKeepInventory(true);
            event.setKeepLevel(true);
        } else if (killer != null) {
            if (plugin.config().dropHeadOnDeath()) {
                drops.add(playerHead(victim));
            }
            if (plugin.config().dropGoldenAppleOnDeath()) {
                drops.add(new ItemStack(Material.GOLDEN_APPLE, 1));
            }
            // 击杀回血
            double heal = plugin.config().healOnKill();
            if (heal > 0 && killer.getGameMode() == GameMode.SURVIVAL) {
                killer.setHealth(Math.min(killer.getMaxHealth(), killer.getHealth() + heal));
            }
            // 职业击杀被动（末影人吸血、恐惧魔王力量、僵尸小回血）
            plugin.kits().onKill(killer);
            plugin.ui().broadcast("&7" + killer.getName() + " &f击杀了 &7" + victim.getName());
        } else {
            plugin.ui().broadcast("&7" + victim.getName() + " &f阵亡了");
        }
        event.deathMessage(null);

        // 复活还是旁观
        boolean canRespawn = plugin.controller().canRespawn(victim);
        if (!canRespawn) {
            plugin.ui().broadcast("&c" + team.coloredName() + " 的 " + victim.getName()
                    + " 已无法复活。");
        }
        // 队伍是否全灭
        if (plugin.teams().aliveCount(team) == 0 && !plugin.controller().canStillRespawn(team)) {
            plugin.teams().markEliminated(team);
            plugin.ui().broadcast("&4" + team.coloredName() + " 已被淘汰。");
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = plugin.controller().arena() == null ? null : event.getPlayer();
        if (player == null || !plugin.teams().hasTeam(player)) {
            return;
        }
        if (!plugin.controller().isRunning()) {
            return;
        }
        if (plugin.controller().canRespawn(player)) {
            // 交给 MatchController 的延迟复活队列处理，这里只把人留在原地等待。
            plugin.controller().scheduleRespawn(player);
            player.setGameMode(GameMode.SPECTATOR);
            int delay = plugin.config().respawnDelaySeconds();
            player.sendMessage(plugin.config().prefix() + "§7你将在 " + delay + " 秒后复活。");
        } else {
            player.setGameMode(GameMode.SPECTATOR);
            if (plugin.config().clearKitOnSpectate()) {
                plugin.kits().clearKit(player);
            }
            player.sendMessage(plugin.config().prefix() + "§c你的队伍已无法复活，转为旁观。");
        }
    }

    /** 玩家头颅：带受害者名字与皮肤。 */
    private ItemStack playerHead(Player victim) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD, 1);
        if (head.getItemMeta() instanceof SkullMeta meta) {
            meta.setOwningPlayer(victim);
            head.setItemMeta(meta);
        }
        return head;
    }
}
