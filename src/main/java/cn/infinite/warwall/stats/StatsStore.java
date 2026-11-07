package cn.infinite.warwall.stats;

import cn.infinite.warwall.WarwallPlugin;
import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * 玩家累计战绩的落盘存储，每人一个 {@code stats/<uuid>.yml}。
 *
 * <p>只存四项累计值 + 名字：这是「跨局累计」用的，单局数据仍在内存里。
 * 写盘发生在收局与停服时，不在事件回调里做 IO。
 */
public final class StatsStore {

    private final WarwallPlugin plugin;
    private final File dir;
    private final Map<UUID, Totals> cache = new LinkedHashMap<>();

    /** 一名玩家的累计战绩。 */
    public record Totals(String name, int kills, int deaths, double damage, int assists, int matches) {

        public static Totals empty(String name) {
            return new Totals(name == null ? "" : name, 0, 0, 0.0, 0, 0);
        }

        public Totals plus(String newName, int addKills, int addDeaths, double addDamage, int addAssists) {
            return new Totals(newName == null || newName.isBlank() ? name : newName,
                    kills + addKills, deaths + addDeaths, damage + addDamage,
                    assists + addAssists, matches + 1);
        }
    }

    public StatsStore(WarwallPlugin plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "stats");
        if (!dir.exists() && !dir.mkdirs()) {
            plugin.getLogger().warning("无法创建统计目录：" + dir.getAbsolutePath());
        }
    }

    public Totals get(UUID uuid, String fallbackName) {
        Totals cached = cache.get(uuid);
        if (cached != null) {
            return cached;
        }
        Totals loaded = read(uuid, fallbackName);
        cache.put(uuid, loaded);
        return loaded;
    }

    /** 累加一局的数据并立即落盘。 */
    public void add(UUID uuid, String name, int kills, int deaths, double damage, int assists) {
        Totals updated = get(uuid, name).plus(name, kills, deaths, damage, assists);
        cache.put(uuid, updated);
        write(uuid, updated);
    }

    /** 停服时统一刷盘。 */
    public void flush() {
        for (Map.Entry<UUID, Totals> entry : cache.entrySet()) {
            write(entry.getKey(), entry.getValue());
        }
    }

    private Totals read(UUID uuid, String fallbackName) {
        File file = new File(dir, uuid + ".yml");
        if (!file.exists()) {
            return Totals.empty(fallbackName);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        return new Totals(
                yaml.getString("name", fallbackName == null ? "" : fallbackName),
                yaml.getInt("kills", 0),
                yaml.getInt("deaths", 0),
                yaml.getDouble("damage", 0.0),
                yaml.getInt("assists", 0),
                yaml.getInt("matches", 0));
    }

    private void write(UUID uuid, Totals totals) {
        File file = new File(dir, uuid + ".yml");
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("name", totals.name());
        yaml.set("kills", totals.kills());
        yaml.set("deaths", totals.deaths());
        yaml.set("damage", Math.round(totals.damage() * 10.0) / 10.0);
        yaml.set("assists", totals.assists());
        yaml.set("matches", totals.matches());
        try {
            yaml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("保存战绩失败：" + file.getName() + "，原因：" + ex.getMessage());
        }
    }
}
