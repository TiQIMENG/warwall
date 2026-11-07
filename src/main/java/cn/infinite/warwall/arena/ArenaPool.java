package cn.infinite.warwall.arena;

import cn.infinite.warwall.WarwallPlugin;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.bukkit.configuration.ConfigurationSection;

/**
 * 地图池：一局从已配置的多张竞技场里随机抽一张。
 *
 * <p>这是「每张地图都应该不一样」的落点：同一台服务器只要在 {@code arenas.yml} 里配了
 * 多张图（各自地形、出生点、墙、核心位置都不同），每局抽到的那张就是不同的地图。
 *
 * <p>抽取规则：
 * <ol>
 *   <li>只考虑 {@code enabled: true} 且世界已加载的图</li>
 *   <li>排除上一局用过的图（只有一张可用时不排除）</li>
 *   <li>在剩下的里面随机</li>
 * </ol>
 */
public final class ArenaPool {

    private final WarwallPlugin plugin;
    /** 上一次用过的地图 id，用于避免连续两局同一张图。 */
    private String lastUsed;

    public ArenaPool(WarwallPlugin plugin) {
        this.plugin = plugin;
    }

    /** 所有可用（enabled + 世界已加载）的地图 id。 */
    public List<String> availableIds() {
        List<String> result = new ArrayList<>();
        ConfigurationSection section = plugin.arenaStore().root().getConfigurationSection("arenas");
        if (section == null) {
            return result;
        }
        for (String id : section.getKeys(false)) {
            if (!section.getBoolean(id + ".enabled", true)) {
                continue;
            }
            String world = section.getString(id + ".world", "");
            if (world.isBlank() || org.bukkit.Bukkit.getWorld(world) == null) {
                continue;
            }
            result.add(id);
        }
        return result;
    }

    /**
     * 抽一张图。
     *
     * @return 抽到的竞技场；没有可用图时返回 null
     */
    public WarwallArena pick() {
        List<String> ids = availableIds();
        if (ids.isEmpty()) {
            return null;
        }
        List<String> candidates = new ArrayList<>(ids);
        if (candidates.size() > 1 && lastUsed != null) {
            candidates.remove(lastUsed);
        }
        Collections.shuffle(candidates);
        String chosen = candidates.get(0);
        List<String> errors = new ArrayList<>();
        WarwallArena arena = plugin.arenaStore().read(chosen, errors);
        if (arena == null) {
            plugin.getLogger().warning("抽到的地图 " + chosen + " 读取失败：" + String.join("；", errors));
            return null;
        }
        lastUsed = chosen;
        plugin.getLogger().info("本局地图：" + chosen + "（共 " + ids.size() + " 张可选）");
        return arena;
    }

    /** 配置里指定固定地图时用它，跳过随机。 */
    public String lastUsed() {
        return lastUsed;
    }

    public void reset() {
        lastUsed = null;
    }
}
