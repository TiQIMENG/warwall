package cn.infinite.warwall.game;

import cn.infinite.warwall.WarwallPlugin;
import cn.infinite.warwall.arena.WarwallArena;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

/**
 * 中央资源刷新与「伊甸之果」。
 *
 * <p>对应数据包里「地图中央定时刷钻石」与死斗阶段「神的意志化为伊甸之果降临场地中央」。
 * 所有定时任务在收局时统一取消，避免残留 task 在下局重复刷。
 */
public final class LootManager {

    private final WarwallPlugin plugin;
    private final List<BukkitTask> tasks = new ArrayList<>();

    public LootManager(WarwallPlugin plugin) {
        this.plugin = plugin;
    }

    /** 进入战斗期时按竞技场配置启动中央刷新。 */
    public void start(WarwallArena arena) {
        stop();
        if (arena == null || arena.diamondDrops().isEmpty()) {
            return;
        }
        for (WarwallArena.DiamondDrop drop : arena.diamondDrops()) {
            Material material = Material.matchMaterial(drop.item());
            if (material == null) {
                plugin.getLogger().warning("中央掉落物材质无效：" + drop.item() + "，已跳过");
                continue;
            }
            long period = Math.max(20L, drop.everySeconds() * 20L);
            BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
                Location location = new Location(
                        org.bukkit.Bukkit.getWorld(arena.worldName()), drop.x(), drop.y(), drop.z());
                if (location.getWorld() == null) {
                    return;
                }
                location.getWorld().dropItemNaturally(location, new ItemStack(material, drop.amount()));
            }, period, period);
            tasks.add(task);
        }
        plugin.getLogger().info("中央刷新已启动：" + tasks.size() + " 个刷新点");
    }

    /** 死斗的伊甸之果：一颗带名字的附魔金苹果，落在地图中央。 */
    public void spawnEdenApple(WarwallArena arena) {
        Location location = centerOf(arena);
        if (location == null) {
            return;
        }
        ItemStack apple = new ItemStack(Material.ENCHANTED_GOLDEN_APPLE, 1);
        ItemMeta meta = apple.getItemMeta();
        if (meta != null) {
            meta.itemName(Component.text("§6§l伊甸之果"));
            meta.lore(List.of(
                    Component.text("§7神的意志化为果实降临。"),
                    Component.text("§7吃下它，就是最后一次赌命。")));
            apple.setItemMeta(meta);
        }
        location.getWorld().dropItemNaturally(location, apple);
    }

    /** 取消所有中央刷新任务。 */
    public void stop() {
        for (BukkitTask task : tasks) {
            if (task != null && !task.isCancelled()) {
                task.cancel();
            }
        }
        tasks.clear();
    }

    private Location centerOf(WarwallArena arena) {
        if (arena == null || arena.center() == null || arena.worldName() == null) {
            return null;
        }
        return arena.center().toLocation(arena.worldName());
    }
}
