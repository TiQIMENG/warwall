package cn.infinite.warwall.economy;

import cn.infinite.warwall.WarwallPlugin;
import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/**
 * 金币与职业解锁等级。
 *
 * <p>每个玩家一个 {@code economy/<uuid>.yml}，内容只有两样：金币数、各职业已升到的等级。
 * 写入时机是「消费/结算时」，不做定时刷盘，避免无谓 IO。
 */
public final class Economy {

    /** 职业升级花费表，下标为「要升到第几级」。取自 MegaArena 的 tier 成本曲线。 */
    private static final int[] UPGRADE_COST = {
            0, 0, 50, 125, 300, 600, 1000, 1500, 2000, 3000
    };

    /** 职业最高等级。 */
    public static final int MAX_LEVEL = UPGRADE_COST.length - 1;

    private final WarwallPlugin plugin;
    private final File dir;
    private final Map<UUID, Account> cache = new LinkedHashMap<>();

    /** 一名玩家的账户。 */
    private record Account(int coins, Map<String, Integer> levels) {
    }

    public Economy(WarwallPlugin plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "economy");
        if (!dir.exists() && !dir.mkdirs()) {
            plugin.getLogger().warning("无法创建经济目录：" + dir.getAbsolutePath());
        }
    }

    // ===== 金币 =====

    public int coinsOf(Player player) {
        return account(player.getUniqueId(), player.getName()).coins();
    }

    public void addCoins(Player player, int amount) {
        if (amount == 0) {
            return;
        }
        Account account = account(player.getUniqueId(), player.getName());
        save(player.getUniqueId(), player.getName(), account.coins() + amount, account.levels());
        plugin.ui().actionBar(player, "&6+" + amount + " 金币 &7(共 " + (account.coins() + amount) + ")");
    }

    /** 扣金币；余额不足返回 false 且不扣。 */
    public boolean takeCoins(Player player, int amount) {
        Account account = account(player.getUniqueId(), player.getName());
        if (account.coins() < amount) {
            return false;
        }
        save(player.getUniqueId(), player.getName(), account.coins() - amount, account.levels());
        return true;
    }

    // ===== 职业等级 =====

    public int levelOf(Player player, String kitId) {
        return account(player.getUniqueId(), player.getName()).levels().getOrDefault(kitId, 1);
    }

    /** 下一级需要多少金币；已满级返回 -1。 */
    public int upgradeCost(Player player, String kitId) {
        int current = levelOf(player, kitId);
        if (current >= MAX_LEVEL) {
            return -1;
        }
        return UPGRADE_COST[current + 1];
    }

    /**
     * 升级职业。
     *
     * @return 成功返回新等级；失败返回 -1（金币不足或已满级）
     */
    public int upgrade(Player player, String kitId) {
        int current = levelOf(player, kitId);
        if (current >= MAX_LEVEL) {
            return -1;
        }
        int cost = UPGRADE_COST[current + 1];
        if (!takeCoins(player, cost)) {
            return -1;
        }
        Account account = account(player.getUniqueId(), player.getName());
        Map<String, Integer> levels = new LinkedHashMap<>(account.levels());
        levels.put(kitId, current + 1);
        save(player.getUniqueId(), player.getName(), account.coins(), levels);
        return current + 1;
    }

    /** 等级带来的技能增幅系数：1 级 1.0，每级 +8%，9 级约 1.64。 */
    public double powerFactor(Player player, String kitId) {
        return 1.0 + (levelOf(player, kitId) - 1) * 0.08;
    }

    /**
     * 结算金币：击杀、助攻、参与、胜利。
     *
     * <p>数值偏小是故意的——职业满级要打几十局，不能一局暴富。
     */
    public void grantMatchRewards(Player player, int kills, int assists, boolean winner) {
        int total = 20 + kills * 12 + assists * 4 + (winner ? 50 : 0);
        addCoins(player, total);
    }

    // ===== 存取 =====

    private Account account(UUID uuid, String name) {
        Account cached = cache.get(uuid);
        if (cached != null) {
            return cached;
        }
        Account loaded = read(uuid, name);
        cache.put(uuid, loaded);
        return loaded;
    }

    private Account read(UUID uuid, String name) {
        File file = new File(dir, uuid + ".yml");
        Map<String, Integer> levels = new LinkedHashMap<>();
        if (!file.exists()) {
            return new Account(0, levels);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        int coins = yaml.getInt("coins", 0);
        if (yaml.isConfigurationSection("levels")) {
            for (String key : yaml.getConfigurationSection("levels").getKeys(false)) {
                levels.put(key, yaml.getInt("levels." + key, 1));
            }
        }
        return new Account(coins, levels);
    }

    private void save(UUID uuid, String name, int coins, Map<String, Integer> levels) {
        cache.put(uuid, new Account(coins, new LinkedHashMap<>(levels)));
        File file = new File(dir, uuid + ".yml");
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("name", name);
        yaml.set("coins", coins);
        for (Map.Entry<String, Integer> entry : levels.entrySet()) {
            yaml.set("levels." + entry.getKey(), entry.getValue());
        }
        try {
            yaml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("保存金币数据失败：" + file.getName() + "，原因：" + ex.getMessage());
        }
    }

    public void forget(UUID uuid) {
        cache.remove(uuid);
    }
}
