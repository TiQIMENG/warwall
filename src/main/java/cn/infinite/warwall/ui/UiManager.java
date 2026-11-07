package cn.infinite.warwall.ui;

import cn.infinite.warwall.WarwallPlugin;
import cn.infinite.warwall.config.WarwallConfig;
import cn.infinite.warwall.core.MatchState;
import cn.infinite.warwall.core.PressureStage;
import cn.infinite.warwall.team.TeamColor;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

/**
 * 所有玩家可见的界面：侧边栏、四队血条、标题、动作条与广播。
 *
 * <p>每个玩家一块独立 scoreboard，不碰主计分板；侧边栏条目用「不可见的唯一字符串」占位，
 * 这是 Bukkit 侧边栏的常规做法，避免同一行重复导致分数行被合并。
 */
public final class UiManager {

    private final WarwallPlugin plugin;

    private final Map<UUID, Scoreboard> boards = new HashMap<>();
    private final Map<TeamColor, BossBar> witherBars = new EnumMap<>(TeamColor.class);

    public UiManager(WarwallPlugin plugin) {
        this.plugin = plugin;
        createBossBars();
    }

    private void createBossBars() {
        for (TeamColor team : TeamColor.values()) {
            BossBar bar = Bukkit.createBossBar(
                    team.coloredName() + " 凋零", colorOf(team), BarStyle.SEGMENTED_10);
            bar.setVisible(true);
            witherBars.put(team, bar);
        }
    }

    private static BarColor colorOf(TeamColor team) {
        return switch (team) {
            case RED -> BarColor.RED;
            case YELLOW -> BarColor.YELLOW;
            case GREEN -> BarColor.GREEN;
            case BLUE -> BarColor.BLUE;
        };
    }

    /** 取玩家自己的计分板，没有就新建一块。 */
    public Scoreboard scoreboardOf(Player player) {
        return boards.computeIfAbsent(player.getUniqueId(),
                key -> Bukkit.getScoreboardManager().getNewScoreboard());
    }

    /** 把四队血条加到某个玩家身上（进服/开局时调用）。 */
    public void attachBossBars(Player player) {
        for (BossBar bar : witherBars.values()) {
            bar.addPlayer(player);
        }
    }

    public void detachBossBars(Player player) {
        for (BossBar bar : witherBars.values()) {
            bar.removePlayer(player);
        }
    }

    /** 阶段切换时的全服播报与标题。 */
    public void onPhaseChange(MatchState state, PressureStage pressure) {
        String subtitle = pressure == null || pressure == PressureStage.NONE
                ? ""
                : pressure.chineseName();
        switch (state) {
            case PREP -> {
                broadcastTitle("&e资源收集阶段", "&7挖掘、合成、加固你的基地");
                broadcast(plugin.config().message("phase.prep", "&e资源收集阶段——挖掘、合成、加固你的基地。"));
            }
            case BATTLE -> {
                broadcastTitle("&c战斗阶段", "&7摧毁敌方凋零与核心");
                broadcast(plugin.config().message("phase.battle", "&6战斗阶段——摧毁敌方凋零。"));
            }
            case SHRINK_1, SHRINK_2 -> {
                broadcastTitle("&e世界边界收缩", state.chineseName());
                broadcast(plugin.config().message("border-shrinking",
                        "&e世界边界正在缩小，注意站位。"));
            }
            case FINAL_FIGHT -> {
                broadcastTitle("&4死斗模式", subtitle.isEmpty() ? "&c不留退路" : subtitle);
                broadcast(plugin.config().message("phase.deathmatch", "&4死斗阶段——没有退路。"));
            }
            case ENDED -> broadcastTitle("&6本局结束", "&7结算中");
            default -> {
                // WAITING / COUNTDOWN 不需要大标题
            }
        }
    }

    public void showTitle(Player player, String title, String subtitle) {
        player.showTitle(Title.title(
                Component.text(WarwallConfig.colorize(title)),
                Component.text(WarwallConfig.colorize(subtitle))));
    }

    public void broadcastTitle(String title, String subtitle) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            showTitle(player, title, subtitle);
        }
    }

    public void broadcast(String message) {
        Bukkit.broadcastMessage(plugin.config().prefix() + WARWALL_PREFIX + WarwallConfig.colorize(message));
    }

    private static final String WARWALL_PREFIX = "";

    /** 刷新侧边栏与四队血条；由 MatchController 定时调用。 */
    public void updateAll() {
        MatchState state = plugin.controller().state();
        long remain = plugin.controller().remainingSeconds();
        String timeText = remain < 0 ? "∞" : format(remain);

        for (Player player : plugin.teams().participants()) {
            refreshSidebar(player, state, timeText);
        }
        refreshBossBars();
    }

    private void refreshSidebar(Player player, MatchState state, String timeText) {
        if (!plugin.config().sidebarEnabled()) {
            return;
        }
        Scoreboard board = scoreboardOf(player);
        Objective objective = board.getObjective("ww_side");
        if (objective == null) {
            objective = board.registerNewObjective("ww_side", Criteria.DUMMY,
                    Component.text("§c§l超级战墙"));
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        }

        List<String> lines = new ArrayList<>();
        lines.add("§7阶段 §f" + state.chineseName());
        lines.add("§7剩余 §f" + timeText);
        lines.add(" ");
        for (TeamColor team : TeamColor.values()) {
            String wither = plugin.withers().isAlive(team)
                    ? String.valueOf(plugin.withers().healthOf(team))
                    : "已毁";
            String nexus = plugin.nexus().enabled()
                    ? (plugin.nexus().isAlive(team) ? String.valueOf(plugin.nexus().healthOf(team)) : "已毁")
                    : "-";
            lines.add(team.chatColor() + abbreviate(team) + " §7存活§f"
                    + plugin.teams().aliveCount(team)
                    + " §7凋零§f" + wither + " §7核§f" + nexus);
        }
        lines.add("  ");
        lines.add("§7击杀 §f" + plugin.stats().killsOf(player.getUniqueId()));

        // 侧边栏按「分数倒序」显示，所以把行号反过来当分数。
        int score = lines.size();
        for (String line : lines) {
            objective.getScore(line).setScore(score--);
        }
        player.setScoreboard(board);
    }

    private static String abbreviate(TeamColor team) {
        return switch (team) {
            case RED -> "红";
            case YELLOW -> "黄";
            case GREEN -> "绿";
            case BLUE -> "蓝";
        };
    }

    private void refreshBossBars() {
        double maxWither = Math.max(1.0, plugin.config().witherMaxHealth());
        int maxNexus = Math.max(1, plugin.config().nexusMaxHealth());
        for (TeamColor team : TeamColor.values()) {
            BossBar bar = witherBars.get(team);
            if (bar == null) {
                continue;
            }
            boolean witherAlive = plugin.withers().isAlive(team);
            int witherHealth = plugin.withers().healthOf(team);
            String nexusText;
            if (!plugin.nexus().enabled()) {
                nexusText = "§7核心未启用";
            } else if (plugin.nexus().isAlive(team)) {
                nexusText = "§7核心 §f" + plugin.nexus().healthOf(team) + "/" + maxNexus;
            } else {
                nexusText = "§c核心已摧毁";
            }
            String witherText = witherAlive
                    ? "§7凋零 §f" + witherHealth + "/" + (int) maxWither
                    : "§c凋零已摧毁";
            bar.setTitle(team.coloredName() + "  " + witherText + "   " + nexusText);
            bar.setProgress(witherAlive ? Math.min(1.0, witherHealth / maxWither) : 0.0);
        }
    }

    /** 动作条：职业能量与冷却提示。 */
    public void actionBar(Player player, String text) {
        if (!plugin.config().actionbarEnergy()) {
            return;
        }
        player.sendActionBar(Component.text(WarwallConfig.colorize(text)));
    }

    /** 玩家下线时清理引用，避免内存泄漏。 */
    public void forget(UUID uuid) {
        boards.remove(uuid);
    }

    public static String format(long seconds) {
        long minutes = seconds / 60;
        long rest = seconds % 60;
        return String.format("%02d:%02d", minutes, rest);
    }
}
