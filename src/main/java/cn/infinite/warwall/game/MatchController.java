package cn.infinite.warwall.game;

import cn.infinite.warwall.WarwallPlugin;
import cn.infinite.warwall.arena.WarwallArena;
import cn.infinite.warwall.config.WarwallConfig;
import cn.infinite.warwall.core.MatchSettings;
import cn.infinite.warwall.core.MatchState;
import cn.infinite.warwall.core.PhaseRules;
import cn.infinite.warwall.core.PressureStage;
import cn.infinite.warwall.team.TeamColor;
import cn.infinite.warwall.world.MapGenerator;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * 单局状态机与时间轴。
 *
 * <p>时间轴骨架以 ShiinaYumeka/wallwar 数据包为准：准备 48000t / 战斗 24000t /
 * 两次缩圈各 12000t / 死斗 3600t，死斗内部再用 {@link PressureStage} 推进压迫阶段。
 *
 * <p>阶段推进一律走 {@link #transition(MatchState)}，不要在别处直接改 {@code state}。
 */
public final class MatchController {

    private final WarwallPlugin plugin;
    private final WarwallConfig config;

    private MatchState state = MatchState.WAITING;
    private PressureStage pressure = PressureStage.NONE;
    private long phaseEndTick = -1;
    private BukkitTask ticker;

    private WarwallArena arena;
    private MatchSettings settings;
    private boolean fastMode;
    private boolean soloMode;

    /** 待复活：UUID → 复活时刻（tick）。 */
    private final Map<UUID, Long> pendingRespawns = new HashMap<>();
    private final Map<TeamColor, Integer> aliveCounts = new EnumMap<>(TeamColor.class);

    public MatchController(WarwallPlugin plugin) {
        this.plugin = plugin;
        this.config = plugin.config();
        this.settings = config.settings();
        for (TeamColor team : TeamColor.values()) {
            aliveCounts.put(team, 0);
        }
    }

    // ===== 查询 =====

    public MatchState state() {
        return state;
    }

    public PressureStage pressure() {
        return pressure;
    }

    public PhaseRules rules() {
        return PhaseRules.rulesFor(state);
    }

    public WarwallArena arena() {
        return arena;
    }

    public MatchSettings settings() {
        return settings;
    }

    public boolean fastMode() {
        return fastMode;
    }

    public boolean soloMode() {
        return soloMode;
    }

    public boolean isRunning() {
        return state.isRunning();
    }

    public long remainingSeconds() {
        if (phaseEndTick < 0) {
            return -1;
        }
        long ticks = phaseEndTick - Bukkit.getCurrentTick();
        return Math.max(0, ticks / 20L);
    }

    public int aliveCount(TeamColor team) {
        return plugin.teams().aliveCount(team);
    }

    public void setAliveCount(TeamColor team, int value) {
        aliveCounts.put(team, Math.max(0, value));
    }

    /** 该队是否还有复活可能（凋零存活）。 */
    public boolean canStillRespawn(TeamColor team) {
        return plugin.withers().isAlive(team);
    }

    // ===== 控制 =====

    public boolean startMatch() {
        if (state.isRunning()) {
            plugin.ui().broadcast("&c已有一局正在进行。");
            return false;
        }
        // 随机地图优先：开了 mapgen 就每局现生成一张新图，完全跳过 arenas.yml。
        if (config.mapgenEnabled()) {
            // 专用世界必须用照搬的 NaturalTerrainGenerator，否则 MapPreparation 会拒绝评分。
            org.bukkit.World genWorld = plugin.ensureMapgenWorld();
            if (genWorld == null) {
                plugin.getLogger().warning("mapgen.world=" + config.mapgenWorld()
                        + " 未加载，回退到手工配置的地图");
            } else {
                MapGenerator.Generated generated = plugin.mapGenerator().generate(
                        genWorld, config.mapgenCenterX(), config.mapgenCenterY(), config.mapgenCenterZ());
                if (generated != null) {
                    arena = generated.arena();
                    plugin.selectArena(arena);
                    // 布局由我们保证对称，地形与资源的公平性交给照搬的 MapPreparation 异步后处理
                    if (config.mapgenFairnessPass()) {
                        plugin.mapGenerator().startFairnessPass(genWorld);
                    }
                }
            }
        }
        // 多地图轮换：开了 rotation 就每局随机抽一张（避开上一局用过的），
        // 关掉就固定用 /warwall setup select 选中的那张。
        if (arena == null && config.arenaRotation()) {
            WarwallArena picked = plugin.arenaPool().pick();
            if (picked != null) {
                arena = picked;
                plugin.selectArena(picked);
            }
        }
        if (arena == null) {
            arena = plugin.selectedArena();
        }
        if (arena == null) {
            plugin.getLogger().warning("未选择竞技场，无法开局");
            Bukkit.broadcastMessage(config.prefix() + "§c还没有选择竞技场，先用 /warwall setup 配置。");
            return false;
        }
        List<String> errors = new ArrayList<>(plugin.validateArena(arena));
        if (!errors.isEmpty()) {
            plugin.getLogger().warning("竞技场校验未通过：" + String.join("；", errors));
            errors.forEach(error -> Bukkit.broadcastMessage(config.prefix() + "§c" + error));
            return false;
        }

        List<Player> participants = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getGameMode() != GameMode.SPECTATOR) {
                participants.add(player);
            }
        }
        int required = Math.max(1, arena.minPlayers());
        // 调试：solo 模式下允许零真人开局，用于无玩家环境跑通状态机与凋零生成。
        if (soloMode) {
            required = 0;
        }
        if (participants.size() < required) {
            Bukkit.broadcastMessage(config.prefix() + "§c人数不足，需要 " + required
                    + " 人，当前 " + participants.size() + " 人。");
            return false;
        }

        plugin.teams().reset();
        plugin.teams().assignTeams(participants);
        plugin.stats().onMatchStart(arena);
        plugin.withers().despawnAll();
        plugin.nexus().reset();

        for (Player player : participants) {
            plugin.teams().applyScoreboard(player);
            plugin.teams().markAlive(player);
            plugin.ui().attachBossBars(player);
            player.setGameMode(GameMode.SURVIVAL);
            player.setHealth(player.getMaxHealth());
            plugin.kits().giveKit(player, plugin.teams().teamOf(player));
            Location spawn = spawnOf(plugin.teams().teamOf(player));
            if (spawn != null) {
                player.teleport(spawn);
            }
        }
        pendingRespawns.clear();
        transition(MatchState.COUNTDOWN);
        return true;
    }

    public void stopMatch() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        if (state.isRunning()) {
            transition(MatchState.ENDED);
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            plugin.ui().detachBossBars(player);
            if (plugin.teams().hasTeam(player)) {
                plugin.kits().clearKit(player);
                player.setGameMode(GameMode.SURVIVAL);
            }
        }
        plugin.withers().despawnAll();
        plugin.walls().restore();
        plugin.border().reset(arena);
        plugin.combat().restoreWorldRules();
        plugin.teams().reset();
        pendingRespawns.clear();
        state = MatchState.WAITING;
        pressure = PressureStage.NONE;
        phaseEndTick = -1;
    }

    /**
     * 强制切到指定阶段。
     *
     * <p>同样走 {@link #transition(MatchState)}；被跳过阶段的必要副作用在这里补齐，
     * 否则直接跳到 BATTLE 时墙还是准备期的样子、凋零也不存在（调试命令常用这条路径）。
     */
    public void forcePhase(MatchState target) {
        if (target == null || target == state) {
            return;
        }
        if (arena == null) {
            arena = config.arenaRotation()
                    ? plugin.arenaPool().pick()
                    : plugin.selectedArena();
        }
        if (target == MatchState.WAITING) {
            stopMatch();
            return;
        }
        if (arena == null) {
            plugin.getLogger().warning("强制切换阶段失败：尚未选择竞技场（先 /warwall setup select <id>）");
            return;
        }
        ensureSkippedEffects(target);
        transition(target);
    }

    /**
     * 补齐被跳过阶段的必要副作用；枚举声明顺序就是时间轴顺序，所以直接比 ordinal。
     *
     * <p>只补「自己这一阶段不会被再次执行」的部分：目标阶段自己的 {@code onPhaseEnter}
     * 已经会做的事（例如进 BATTLE 时倒墙、进 PREP 时捕获墙）不在这里重复，
     * 否则同一次切换会倒两次墙、打一条「墙未捕获」的误导性告警。
     */
    private void ensureSkippedEffects(MatchState target) {
        if (target.ordinal() <= MatchState.PREP.ordinal() || target == MatchState.ENDED) {
            // COUNTDOWN / PREP 由各自 onPhaseEnter 完成；ENDED 是收尾阶段，没有前置条件
            return;
        }
        if (plugin.walls().capturedBlocks() == 0) {
            plugin.walls().capture();
        }
        if (plugin.withers().witherOf(TeamColor.RED) == null) {
            plugin.withers().spawnAll(arena);
            plugin.nexus().init(arena);
        }
        if (target.ordinal() > MatchState.BATTLE.ordinal() && !plugin.walls().isDropped()) {
            plugin.walls().drop();
        }
    }

    public void skipPhase() {
        phaseEndTick = Bukkit.getCurrentTick();
    }

    public void setFastMode(boolean enabled) {
        this.fastMode = enabled;
        this.settings = enabled ? config.settings().fastMode() : config.settings();
        if (state.isRunning()) {
            phaseEndTick = Bukkit.getCurrentTick() + settings.ticksOf(state);
        }
    }

    public void setSoloMode(boolean enabled) {
        this.soloMode = enabled;
    }

    public void setEnergy(Player player, int value) {
        plugin.kits().setEnergy(player, value);
    }

    public void killWither(TeamColor team) {
        plugin.withers().kill(team);
    }

    public String statusLine() {
        StringBuilder sb = new StringBuilder();
        sb.append("阶段=").append(state.name()).append('(').append(state.chineseName()).append(')');
        long remain = remainingSeconds();
        sb.append(remain >= 0 ? " 剩余=" + remain + "s" : " 剩余=∞");
        if (state == MatchState.FINAL_FIGHT) {
            sb.append(" 压迫=").append(pressure.name());
        }
        sb.append(" 竞技场=").append(arena == null ? "未选择" : arena.id());
        for (TeamColor team : TeamColor.values()) {
            sb.append(' ').append(team.id()).append("=存活").append(aliveCount(team))
                    .append("/凋零").append(plugin.withers().isAlive(team)
                            ? plugin.withers().healthOf(team) : 0)
                    .append("/核").append(plugin.nexus().enabled() && plugin.nexus().isAlive(team)
                            ? plugin.nexus().healthOf(team) : 0);
        }
        return sb.toString();
    }

    // ===== 阶段转移 =====

    void transition(MatchState target) {
        if (target == state) {
            return;
        }
        MatchState previous = state;
        onPhaseEnd(previous);
        state = target;
        if (target == MatchState.FINAL_FIGHT) {
            pressure = PressureStage.NONE;
        }
        if (!target.isRunning()) {
            phaseEndTick = -1;
        } else {
            long ticks = settings.ticksOf(target);
            phaseEndTick = ticks < 0 ? -1 : Bukkit.getCurrentTick() + ticks;
        }
        onPhaseEnter(target);
        ensureTicker();
        plugin.ui().onPhaseChange(target, pressure);
        plugin.ui().updateAll();
        if (config.debug()) {
            plugin.getLogger().info("阶段转移 " + previous + " → " + target);
        }
    }

    /** 进入压迫阶段的下一档。 */
    private void advancePressure() {
        PressureStage next = pressure.next();
        if (next == pressure) {
            return;
        }
        pressure = next;
        int seconds = settings.secondsOf(next);
        phaseEndTick = seconds < 0 ? -1 : Bukkit.getCurrentTick() + seconds * 20L;
        onPressureEnter(next);
        plugin.ui().onPhaseChange(MatchState.FINAL_FIGHT, next);
        plugin.ui().updateAll();
    }

    private void onPressureEnter(PressureStage stage) {
        switch (stage) {
            case TEAM_UPGRADE -> {
                plugin.nexus().refreshTeamBuff();
                plugin.ui().broadcast("&b团队增益已刷新。");
            }
            case POISON -> {
                applyGlobalEffect(org.bukkit.potion.PotionEffectType.POISON, 1);
                plugin.ui().broadcast("&2全体中毒开始。");
            }
            case HUNGER -> {
                applyGlobalEffect(org.bukkit.potion.PotionEffectType.HUNGER, 1);
                plugin.ui().broadcast("&6全体饥饿开始。");
            }
            case WITHER -> {
                applyGlobalEffect(org.bukkit.potion.PotionEffectType.WITHER, 1);
                plugin.ui().broadcast("&8全体凋零开始。");
            }
            case EDEN_APPLE -> {
                plugin.loot().spawnEdenApple(arena);
                // 融合 Boss 模式：死斗果实阶段中央出现深渊守卫，逼玩家做取舍。
                plugin.boss().spawn(arena, cn.infinite.warwall.boss.BossManager.Form.ABYSS);
                plugin.ui().broadcast("&d伊甸之果已降临场地中央。");
            }
            case LAST_STAND -> {
                // 最终死斗：Boss 换成觉醒形态，逼最后一波团战。
                plugin.boss().spawn(arena, cn.infinite.warwall.boss.BossManager.Form.AWAKENED);
                plugin.ui().broadcast("&4最终死斗开始。");
            }
            default -> {
                // NONE 不做事
            }
        }
    }

    private void applyGlobalEffect(org.bukkit.potion.PotionEffectType type, int amplifier) {
        for (Player player : plugin.teams().participants()) {
            player.addPotionEffect(new org.bukkit.potion.PotionEffect(
                    type, 20 * 60, amplifier, true, false, false));
        }
    }

    void onPhaseEnter(MatchState entered) {
        switch (entered) {
            case COUNTDOWN -> {
                if (arena != null) {
                    plugin.combat().applyWorldRules(arenaWorld(), entered);
                    plugin.border().applyBase(arena);
                    plugin.walls().capture();
                }
                plugin.ui().broadcast("&e本局开始，准备阶段即将开始。");
            }
            case PREP -> {
                plugin.combat().applyWorldRules(arenaWorld(), entered);
                plugin.withers().spawnAll(arena);
                plugin.nexus().init(arena);
                plugin.withers().setAttackable(false);
                plugin.nexus().setAttackable(false);
                plugin.walls().capture();
                // 超级战墙的准备期标志：全员急迫 II，方便快速挖矿建造防御。
                applyPrepHaste();
                ensureTicker();
            }
            case BATTLE -> {
                plugin.combat().onPhaseChange(entered);
                plugin.walls().drop();
                plugin.withers().setAttackable(true);
                plugin.nexus().setAttackable(true);
                // 中央资源刷新从战斗期开始，对应数据包「墙倒后地图中央开始刷资源」。
                plugin.loot().start(arena);
            }
            case SHRINK_1 -> {
                plugin.border().shrinkStep(0);
                plugin.combat().onPhaseChange(entered);
            }
            case SHRINK_2 -> {
                plugin.border().shrinkStep(1);
                plugin.combat().onPhaseChange(entered);
            }
            case FINAL_FIGHT -> {
                plugin.border().finalShrink();
                plugin.withers().setAttackable(false);
                teleportEveryoneToCenter();
            }
            case ENDED -> {
                grantMatchRewards();
                plugin.stats().onMatchEnd();
                plugin.loot().stop();
                plugin.boss().despawn();
                plugin.buildings().clear();
                plugin.glow().stop();
                plugin.withers().despawnAll();
                plugin.walls().restore();
                plugin.border().reset(arena);
                plugin.combat().restoreWorldRules();
                for (Player player : Bukkit.getOnlinePlayers()) {
                    plugin.ui().detachBossBars(player);
                }
            }
            default -> {
                // WAITING 无事可做
            }
        }
    }

    void onPhaseEnd(MatchState leaving) {
        switch (leaving) {
            case BATTLE -> plugin.getLogger().info("战斗阶段结束");
            case FINAL_FIGHT -> pressure = PressureStage.NONE;
            default -> {
                // 其余阶段无需清理
            }
        }
    }

    // ===== 定时器 =====

    private void ensureTicker() {
        if (ticker != null && !ticker.isCancelled()) {
            return;
        }
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    private void tick() {
        if (!state.isRunning()) {
            return;
        }
        long now = Bukkit.getCurrentTick();
        int secondCounter = (int) (now % 20);

        if (secondCounter == 0) {
            plugin.ui().updateAll();
            plugin.nexus().refreshTeamBuff();
            // 建筑光环每秒结算一次；Boss 每秒重新锁定最近的敌对玩家。
            plugin.buildings().tick();
            plugin.boss().tick();
            for (Player online : plugin.teams().participants()) {
                plugin.items().tickArmor(online);
            }
        }
        plugin.withers().tick();
        processRespawns(now);

        if (phaseEndTick >= 0 && now >= phaseEndTick) {
            onPhaseTimeout();
        }
        checkWin();
    }

    private void onPhaseTimeout() {
        if (state == MatchState.FINAL_FIGHT) {
            if (pressure == PressureStage.LAST_STAND) {
                // 最终死斗没有时间上限，只靠胜负判定结束
                return;
            }
            advancePressure();
            return;
        }
        transition(state.next());
    }

    private void checkWin() {
        if (!state.isCombat()) {
            return;
        }
        int surviving = 0;
        for (TeamColor team : TeamColor.values()) {
            if (plugin.teams().aliveCount(team) > 0 || canStillRespawn(team)) {
                surviving++;
            }
        }
        if (surviving <= 1) {
            transition(MatchState.ENDED);
        }
    }

    private void processRespawns(long now) {
        if (pendingRespawns.isEmpty()) {
            return;
        }
        List<UUID> ready = new ArrayList<>();
        for (Map.Entry<UUID, Long> entry : pendingRespawns.entrySet()) {
            if (now >= entry.getValue()) {
                ready.add(entry.getKey());
            }
        }
        for (UUID uuid : ready) {
            pendingRespawns.remove(uuid);
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                continue;
            }
            respawnPlayer(player);
        }
    }

    /** 安排一次延迟复活。 */
    public void scheduleRespawn(Player player) {
        int delay = Math.max(0, config.respawnDelaySeconds());
        pendingRespawns.put(player.getUniqueId(), Bukkit.getCurrentTick() + delay * 20L);
    }

    /** 真正把玩家放回场上。 */
    public void respawnPlayer(Player player) {
        player.setGameMode(GameMode.SURVIVAL);
        player.setHealth(player.getMaxHealth());
        player.setFoodLevel(20);
        TeamColor team = plugin.teams().teamOf(player);
        if (team != null) {
            plugin.kits().giveKit(player, team);
        }
        Location target = respawnLocation(team);
        if (target != null) {
            player.teleport(target);
        }
        int invulnerable = Math.max(0, config.respawnInvulnerableSeconds());
        if (invulnerable > 0) {
            player.setInvulnerable(true);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    player.setInvulnerable(false);
                }
            }, invulnerable * 20L);
        }
        plugin.teams().markAlive(player);
    }

    private Location respawnLocation(TeamColor team) {
        if (team == null) {
            return null;
        }
        WarwallArena.TeamSetup setup = arena == null ? null : arena.team(team.id());
        if (setup == null) {
            return null;
        }
        Location fallback = setup.respawn() != null
                ? setup.respawn().toLocation(arena.worldName())
                : spawnOf(team);
        if (plugin.nexus().canUseSpawnPoints(team)) {
            return fallback;
        }
        // 核心被毁：复活点散落在核心残骸附近。
        return plugin.nexus().scatterRespawn(team, arena.worldName(), fallback);
    }

    private Location spawnOf(TeamColor team) {
        if (arena == null || team == null) {
            return null;
        }
        WarwallArena.TeamSetup setup = arena.team(team.id());
        return setup == null || setup.spawn() == null ? null : setup.spawn().toLocation(arena.worldName());
    }

    /** 准备期急迫 II：超级战墙的标志性增益，让 5 分钟足够挖矿与建造。 */
    private void applyPrepHaste() {
        int duration = Math.max(20, settings.prepSeconds() * 20);
        for (Player player : plugin.teams().participants()) {
            player.addPotionEffect(new org.bukkit.potion.PotionEffect(
                    org.bukkit.potion.PotionEffectType.HASTE, duration, 1, true, false, false));
        }
    }

    /** 结算金币：参与 + 击杀 + 助攻 + 胜利加成。 */
    private void grantMatchRewards() {
        if (!plugin.config().statsEnabled()) {
            return;
        }
        TeamColor winner = plugin.teams().leadingTeam();
        for (Player player : plugin.teams().participants()) {
            TeamColor team = plugin.teams().teamOf(player);
            boolean won = team != null && team == winner;
            plugin.economy().grantMatchRewards(player,
                    plugin.stats().killsOf(player.getUniqueId()),
                    plugin.stats().assistsOf(player.getUniqueId()),
                    won);
        }
    }

    private void teleportEveryoneToCenter() {        if (arena == null || arena.center() == null) {
            return;
        }
        Location center = arena.center().toLocation(arena.worldName());
        if (center == null) {
            return;
        }
        for (Player player : plugin.teams().participants()) {
            player.teleport(center.clone().add(0, 2, 0));
        }
    }

    private org.bukkit.World arenaWorld() {
        return arena == null ? null : Bukkit.getWorld(arena.worldName());
    }

    /** 死亡时是否还能复活。 */
    public boolean canRespawn(Player player) {
        TeamColor team = plugin.teams().teamOf(player);
        if (team == null) {
            return false;
        }
        return switch (rules().respawn()) {
            case ALWAYS -> true;
            case WHILE_OWN_WITHER_ALIVE -> plugin.withers().isAlive(team);
            case NEVER -> false;
        };
    }
}
