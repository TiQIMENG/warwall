package cn.infinite.warwall.game;

import cn.infinite.warwall.WarwallPlugin;
import cn.infinite.warwall.arena.WarwallArena;
import java.util.List;
import java.util.Map;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.configuration.ConfigurationSection;

/**
 * 世界边界与缩圈。
 *
 * <p>缩圈全部走 {@code WorldBorder#changeSize(double, long)}（1.21.11 首选，单位 tick）；
 * {@code setSize(double,long)} 自 1.21.11 起已标记待移除，不要用。
 */
public final class BorderManager {

    private final WarwallPlugin plugin;

    public BorderManager(WarwallPlugin plugin) {
        this.plugin = plugin;
    }

    /** 开局时把边界设到基础半径。 */
    public void applyBase(WarwallArena arena) {
        World world = world(arena);
        if (world == null) {
            return;
        }
        WorldBorder border = world.getWorldBorder();
        border.setCenter(arena.borderCenterX(), arena.borderCenterZ());
        border.setSize(plugin.config().borderBaseRadius());
        border.setDamageAmount(plugin.config().borderDamagePerSecond());
        border.setDamageBuffer(0.0);
        border.setWarningDistance(plugin.config().borderWarningDistance());
    }

    /**
     * 按配置里的缩圈步骤执行下一次收缩。
     *
     * @param stepIndex 第几步（从 0 开始）
     */
    public void shrinkStep(int stepIndex) {
        WarwallArena arena = plugin.controller().arena();
        World world = world(arena);
        if (world == null) {
            return;
        }
        List<Map<?, ?>> steps = plugin.getConfig().getMapList("border.shrink-steps");
        if (stepIndex < 0 || stepIndex >= steps.size()) {
            return;
        }
        Map<?, ?> step = steps.get(stepIndex);
        double radius = step.get("radius") instanceof Number number ? number.doubleValue() : 150.0;
        int seconds = step.get("duration-seconds") instanceof Number number ? number.intValue() : 60;
        shrinkTo(world, radius, seconds);
    }

    /** 死斗：把边界收到终点并与数据包一致地拉高圈外伤害。 */
    public void finalShrink() {
        World world = world(plugin.controller().arena());
        if (world == null) {
            return;
        }
        shrinkTo(world, plugin.config().deathmatchRadius(), plugin.config().deathmatchShrinkSeconds());
        world.getWorldBorder().setDamageAmount(plugin.config().deathmatchDamagePerSecond());
    }

    private void shrinkTo(World world, double radius, int seconds) {
        WorldBorder border = world.getWorldBorder();
        long ticks = Math.max(1L, seconds * 20L);
        // changeSize 的单位是 tick，不是秒；这里一定要乘 20。
        border.changeSize(radius, ticks);
    }

    /** 收局时把边界恢复到一个很大的值，避免影响下一局或其它玩家。 */
    public void reset(WarwallArena arena) {
        World world = world(arena);
        if (world == null) {
            return;
        }
        world.getWorldBorder().setSize(29_999_984.0);
        world.getWorldBorder().setDamageAmount(0.2);
    }

    /** 读配置的缩圈步数，供阶段推进判断下一步是否存在。 */
    public int shrinkStepCount() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("border");
        if (section == null) {
            return 0;
        }
        return plugin.getConfig().getMapList("border.shrink-steps").size();
    }

    private World world(WarwallArena arena) {
        if (arena == null || arena.worldName() == null) {
            return null;
        }
        return org.bukkit.Bukkit.getWorld(arena.worldName());
    }
}
