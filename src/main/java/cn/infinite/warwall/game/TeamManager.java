package cn.infinite.warwall.game;

import cn.infinite.warwall.WarwallPlugin;
import cn.infinite.warwall.team.TeamColor;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

/**
 * 队伍归属、存活统计与计分板 Team 绑定。
 *
 * <p>计分板用**每个玩家独立的一块**，不碰主计分板，避免和其它插件抢侧边栏。
 * 队伍颜色与友伤设置全部落在这块独立板上。
 */
public final class TeamManager {

    private final WarwallPlugin plugin;

    /** 玩家 → 队伍。以 UUID 为键，断线重连不会丢归属。 */
    private final Map<UUID, TeamColor> playerTeams = new HashMap<>();

    /** 队伍 → 在线存活玩家。 */
    private final Map<TeamColor, List<UUID>> alivePlayers = new EnumMap<>(TeamColor.class);

    /** 队伍 → 是否已被淘汰。 */
    private final Map<TeamColor, Boolean> eliminated = new EnumMap<>(TeamColor.class);

    public TeamManager(WarwallPlugin plugin) {
        this.plugin = plugin;
        reset();
    }

    /** 清空所有队伍归属与统计，用于开局前与收局后。 */
    public void reset() {
        playerTeams.clear();
        for (TeamColor team : TeamColor.values()) {
            alivePlayers.computeIfAbsent(team, key -> new ArrayList<>()).clear();
            eliminated.put(team, false);
        }
    }

    /**
     * 自动分队：按「当前人数最少的队伍优先」分配，保证四队人数尽量相等。
     *
     * @param players 本局参与者
     */
    public void assignTeams(List<Player> players) {
        playerTeams.clear();
        for (TeamColor team : TeamColor.values()) {
            alivePlayers.get(team).clear();
            eliminated.put(team, false);
        }
        // 先按在线时长/随机打散，避免固定顺序导致总是同一批人同队。
        List<Player> shuffled = new ArrayList<>(players);
        java.util.Collections.shuffle(shuffled);
        int index = 0;
        for (Player player : shuffled) {
            TeamColor team = leastPopulatedTeam();
            playerTeams.put(player.getUniqueId(), team);
            alivePlayers.get(team).add(player.getUniqueId());
            index++;
            if (index >= shuffled.size()) {
                break;
            }
        }
    }

    private TeamColor leastPopulatedTeam() {
        TeamColor best = TeamColor.RED;
        int bestSize = Integer.MAX_VALUE;
        for (TeamColor team : TeamColor.values()) {
            int size = alivePlayers.get(team).size();
            if (size < bestSize) {
                bestSize = size;
                best = team;
            }
        }
        return best;
    }

    public TeamColor teamOf(Player player) {
        return player == null ? null : playerTeams.get(player.getUniqueId());
    }

    public TeamColor teamOf(UUID uuid) {
        return uuid == null ? null : playerTeams.get(uuid);
    }

    public boolean hasTeam(Player player) {
        return teamOf(player) != null;
    }

    /** 本局参与者（有队伍）的在线玩家。 */
    public List<Player> participants() {
        List<Player> result = new ArrayList<>();
        for (UUID uuid : playerTeams.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                result.add(player);
            }
        }
        return result;
    }

    public List<Player> onlineOf(TeamColor team) {
        List<Player> result = new ArrayList<>();
        for (UUID uuid : alivePlayers.get(team)) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                result.add(player);
            }
        }
        return result;
    }

    /** 该队还有几个「能复活或还活着」的人。凋零被毁后由 MatchController 回调 {@link #markLost}。 */
    public int aliveCount(TeamColor team) {
        return alivePlayers.get(team).size();
    }

    /** 记一次死亡：从存活里摘掉，但保留队伍归属（重连还要用）。 */
    public void markDead(Player player) {
        TeamColor team = teamOf(player);
        if (team != null) {
            alivePlayers.get(team).remove(player.getUniqueId());
        }
    }

    /** 记一次复活。 */
    public void markAlive(Player player) {
        TeamColor team = teamOf(player);
        if (team != null && !alivePlayers.get(team).contains(player.getUniqueId())) {
            alivePlayers.get(team).add(player.getUniqueId());
        }
    }

    public boolean isEliminated(TeamColor team) {
        return Boolean.TRUE.equals(eliminated.get(team));
    }

    public void markEliminated(TeamColor team) {
        eliminated.put(team, true);
    }

    /** 还有队伍没被淘汰的计数，用于胜负判定。 */
    public int remainingTeams() {
        int count = 0;
        for (TeamColor team : TeamColor.values()) {
            if (!isEliminated(team) && (aliveCount(team) > 0 || plugin.controller().canStillRespawn(team))) {
                count++;
            }
        }
        return count;
    }

    /** 当前领先队伍（存活最多）；全平局返回第一队。 */
    public TeamColor leadingTeam() {
        TeamColor best = TeamColor.RED;
        int bestCount = -1;
        for (TeamColor team : TeamColor.values()) {
            int count = aliveCount(team);
            if (count > bestCount) {
                bestCount = count;
                best = team;
            }
        }
        return best;
    }

    /**
     * 给玩家建独立计分板并绑定队伍颜色。
     *
     * <p>玩家已经有一块同名队伍的板时不重复建，避免重连后队伍叠加。
     */
    public void applyScoreboard(Player player) {
        TeamColor team = teamOf(player);
        if (team == null) {
            return;
        }
        Scoreboard board = plugin.ui().scoreboardOf(player);
        String teamName = "ww_" + team.id();
        Team scoreboardTeam = board.getTeam(teamName);
        if (scoreboardTeam == null) {
            scoreboardTeam = board.registerNewTeam(teamName);
        }
        scoreboardTeam.setColor(team.chatColor());
        scoreboardTeam.prefix(Component.text(team.chineseName(), adventureColor(team)));
        scoreboardTeam.setAllowFriendlyFire(plugin.config().friendlyFire());
        if (!scoreboardTeam.hasEntry(player.getName())) {
            scoreboardTeam.addEntry(player.getName());
        }
        player.setScoreboard(board);
    }

    /** TeamColor → Adventure 颜色；Adventure 没有 byName，这里显式映射。 */
    private static net.kyori.adventure.text.format.TextColor adventureColor(TeamColor team) {
        return switch (team) {
            case RED -> net.kyori.adventure.text.format.NamedTextColor.RED;
            case YELLOW -> net.kyori.adventure.text.format.NamedTextColor.YELLOW;
            case GREEN -> net.kyori.adventure.text.format.NamedTextColor.GREEN;
            case BLUE -> net.kyori.adventure.text.format.NamedTextColor.BLUE;
        };
    }

    /** 队伍名 → 该队成员名单（供侧边栏渲染）。 */
    public Map<String, Integer> snapshotAlive() {
        Map<String, Integer> snapshot = new LinkedHashMap<>();
        for (TeamColor team : TeamColor.values()) {
            snapshot.put(team.id(), aliveCount(team));
        }
        return snapshot;
    }
}
