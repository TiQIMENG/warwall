package cn.infinite.warwall.stats;

import cn.infinite.warwall.WarwallPlugin;
import cn.infinite.warwall.arena.WarwallArena;
import cn.infinite.warwall.team.TeamColor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * 对局统计：击杀、死亡、伤害、助攻。
 *
 * <p>本期只做内存统计 + 结算播报，**不落盘**。PLAN 里的 YAML 持久化留到下一期，
 * 因为落盘格式一旦定下来就要长期兼容，宁可不做也不要先定死。
 */
public final class StatsManager {

    private final WarwallPlugin plugin;
    private final StatsStore store;

    private final Map<UUID, Integer> kills = new HashMap<>();
    private final Map<UUID, Integer> deaths = new HashMap<>();
    private final Map<UUID, Double> damage = new HashMap<>();
    private final Map<UUID, Integer> assists = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();

    public StatsManager(WarwallPlugin plugin) {
        this.plugin = plugin;
        this.store = plugin.config().statsEnabled() ? new StatsStore(plugin) : null;
    }

    public void onMatchStart(WarwallArena arena) {
        kills.clear();
        deaths.clear();
        damage.clear();
        assists.clear();
        names.clear();
    }

    public void onKill(Player killer, Player victim) {
        if (killer != null) {
            kills.merge(killer.getUniqueId(), 1, Integer::sum);
            names.put(killer.getUniqueId(), killer.getName());
        }
        if (victim != null) {
            deaths.merge(victim.getUniqueId(), 1, Integer::sum);
            names.put(victim.getUniqueId(), victim.getName());
        }
    }

    public void onDamage(Player damager, double amount) {
        if (damager == null || amount <= 0) {
            return;
        }
        damage.merge(damager.getUniqueId(), amount, Double::sum);
        names.put(damager.getUniqueId(), damager.getName());
    }

    public void onAssist(Player player) {
        if (player == null) {
            return;
        }
        assists.merge(player.getUniqueId(), 1, Integer::sum);
        names.put(player.getUniqueId(), player.getName());
    }

    public int killsOf(UUID uuid) {
        return kills.getOrDefault(uuid, 0);
    }

    public int deathsOf(UUID uuid) {
        return deaths.getOrDefault(uuid, 0);
    }

    public double damageOf(UUID uuid) {
        return damage.getOrDefault(uuid, 0.0);
    }

    public int assistsOf(UUID uuid) {
        return assists.getOrDefault(uuid, 0);
    }

    /** 结算：把总览与 MVP 播报出去。 */
    public void onMatchEnd() {
        if (!plugin.config().statsEnabled()) {
            return;
        }
        plugin.ui().broadcast("&6===== 本局结算 =====");
        for (TeamColor team : TeamColor.values()) {
            int alive = plugin.teams().aliveCount(team);
            plugin.ui().broadcast("&7" + team.coloredName() + " &7存活 &f" + alive
                    + " &7凋零 &f" + (plugin.withers().isAlive(team) ? plugin.withers().healthOf(team) : 0)
                    + " &7核心 &f" + (plugin.nexus().enabled() && plugin.nexus().isAlive(team)
                    ? plugin.nexus().healthOf(team) : 0));
        }
        List<Map.Entry<UUID, Integer>> ranking = new ArrayList<>(kills.entrySet());
        ranking.sort(Comparator.comparingInt(Map.Entry<UUID, Integer>::getValue).reversed());
        int index = 0;
        for (Map.Entry<UUID, Integer> entry : ranking) {
            if (index >= 5) {
                break;
            }
            String name = names.getOrDefault(entry.getKey(), "未知玩家");
            plugin.ui().broadcast("&e#" + (index + 1) + " &f" + name
                    + " &7击杀 &f" + entry.getValue()
                    + " &7死亡 &f" + deathsOf(entry.getKey())
                    + " &7伤害 &f" + Math.round(damageOf(entry.getKey()))
                    + " &7助攻 &f" + assists.getOrDefault(entry.getKey(), 0));
            index++;
        }
        if (plugin.config().announceMvp() && !ranking.isEmpty()) {
            UUID mvp = ranking.get(0).getKey();
            plugin.ui().broadcast("&6本局 MVP：&f" + names.getOrDefault(mvp, "未知玩家"));
        }
        persistToStore();
    }

    /** 把本局数据累加到跨局战绩文件。 */
    private void persistToStore() {
        if (store == null) {
            return;
        }
        for (UUID uuid : names.keySet()) {
            store.add(uuid, names.get(uuid),
                    killsOf(uuid), deathsOf(uuid), damageOf(uuid), assists.getOrDefault(uuid, 0));
        }
    }

    /** 跨局累计战绩；没有记录时返回空。 */
    public StatsStore.Totals totalsOf(Player player) {
        return store == null
                ? StatsStore.Totals.empty(player.getName())
                : store.get(player.getUniqueId(), player.getName());
    }

    /** 停服前刷盘。 */
    public void flush() {
        if (store != null) {
            store.flush();
        }
    }

    /** 给 /warwall status 用的一行摘要。 */
    public String summary() {
        Player top = null;
        int best = -1;
        for (Map.Entry<UUID, Integer> entry : kills.entrySet()) {
            if (entry.getValue() > best) {
                best = entry.getValue();
                top = Bukkit.getPlayer(entry.getKey());
            }
        }
        return top == null ? "暂无击杀" : ("击杀榜首 " + top.getName() + " " + best);
    }
}
