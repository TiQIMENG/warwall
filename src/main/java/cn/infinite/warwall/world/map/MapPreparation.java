package cn.infinite.warwall.world.map;

import cn.infinite.warwall.world.map.ArenaGeometry.ChunkPosition;
import cn.infinite.warwall.world.room.CombatTeam;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class MapPreparation implements AutoCloseable {
    public record Result(boolean accepted, boolean retrySeed, String reason, int centerY,
                         ResourceMetrics metrics, String diagnostics, TerritoryScoring.Report initialScores,
                         TerritoryScoring.Report finalScores, double targetScore) { }
    private enum Phase { TERRAIN, RESOURCES, VERIFY }
    private static final class UnsuitableTerrainException extends IllegalStateException {
        UnsuitableTerrainException(String message) { super(message); }
    }
    private final JavaPlugin plugin;
    private final World world;
    private final ExecutorService worker;
    private final ScoreSettings scoreSettings;
    private final TerritoryBalancePlanner planner;
    private final org.bukkit.Registry<org.bukkit.generator.structure.Structure> structureRegistry;
    private final Consumer<Result> completion;
    private final int concurrency, jobsPerTick;
    private final long editBudgetNanos;
    private final Random random;
    private final ArrayDeque<ChunkPosition> chunks = new ArrayDeque<>();
    private final ArrayDeque<Runnable> changes = new ArrayDeque<>();
    private final Set<ChunkPosition> dirty = new LinkedHashSet<>();
    private final ChunkMetricsIndex index = new ChunkMetricsIndex();
    private final Set<Future<?>> scans = new LinkedHashSet<>();
    private ResourceMetrics metrics = new ResourceMetrics();
    private int running, done, total, centerY, terrainChunks, resourceChunks, verifiedChunks;
    private Phase phase;
    private final EnumMap<CombatTeam, TerritoryBalancePlanner.Budget> budgets = new EnumMap<>(CombatTeam.class);
    private final EnumMap<CombatTeam, Integer> groveCursor = new EnumMap<>(CombatTeam.class);
    private final EnumMap<CombatTeam, ArenaGeometry.ChunkPosition> groves = new EnumMap<>(CombatTeam.class);
    private final List<OriginalRuin.Placed> placedRuins = new ArrayList<>();
    private List<TerritoryStructure> protectedStructures = List.of();
    private final java.util.Map<String, OriginalRuin.Template> originalTemplates = new java.util.HashMap<>();
    private TerritoryScoring.Report initialScores, finalScores;
    private double targetScore = Double.NEGATIVE_INFINITY;
    private int compensationRounds;
    private long startedNanos;
    private volatile boolean closed;
    private volatile String progress = "等待准备";
    private BukkitTask edits, watchdog;

    public MapPreparation(JavaPlugin plugin, World world, ExecutorService worker, Consumer<Result> completion) {
        this.plugin = plugin;
        this.world = world;
        this.worker = worker;
        this.completion = completion;
        concurrency = Math.clamp(plugin.getConfig().getInt("map.pregeneration-concurrency", 2), 1, 4);
        jobsPerTick = Math.clamp(plugin.getConfig().getInt("map.block-jobs-per-tick", 16), 1, 64);
        editBudgetNanos = Math.clamp(plugin.getConfig().getInt("map.edit-budget-millis", 4), 1, 40) * 1_000_000L;
        scoreSettings = ScoreSettings.from(plugin.getConfig());
        planner = new TerritoryBalancePlanner(scoreSettings);
        structureRegistry = io.papermc.paper.registry.RegistryAccess.registryAccess().getRegistry(io.papermc.paper.registry.RegistryKey.STRUCTURE);
        for (CombatTeam team : CombatTeam.values()) budgets.put(team, TerritoryBalancePlanner.Budget.empty());
        random = new Random(world.getSeed() ^ 0x763ad9517L);
    }
    public String progress() { return progress; }
    public void start() {
        if (phase != null || closed) throw new IllegalStateException("地图准备已启动或关闭");
        startedNanos = System.nanoTime();
        if (!(world.getGenerator() instanceof NaturalTerrainGenerator)) {
            fail("主世界未启用 WarwallTool 地形生成器", null);
            return;
        }
        if (!world.canGenerateStructures()) {
            fail("主世界关闭了自然结构生成；请检查 generate-structures 配置后在空服状态重建", null);
            return;
        }
        try {
            for (var spec : OriginalRuinCatalog.compensation()) originalTemplates.put(spec.key(), OriginalRuin.load(spec));
        } catch (RuntimeException exception) { fail("原创遗迹模板或奖励表加载失败，保留现场", exception); return; }
        int timeout = Math.clamp(plugin.getConfig().getInt("map.preparation-timeout-seconds", 1800), 60, 7200);
        watchdog = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (System.nanoTime() - startedNanos >= timeout * 1_000_000_000L) fail("地图准备超时，保留当前地图等待管理员检查", null);
        }, 20, 20);
        try {
            centerY = world.getHighestBlockYAt(0, 0, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
            if (centerY < 62 || centerY > 110) {
                reject("中心高度不适合战墙出生点");
                return;
            }
            scan(Phase.TERRAIN, ArenaGeometry.scanOrder());
        } catch (RuntimeException exception) { fail("中心地形检查失败", exception); }
    }
    private void scan(Phase next, List<ChunkPosition> positions) {
        if (running != 0) throw new IllegalStateException("前一阶段扫描尚未结束");
        phase = next;
        chunks.clear();
        chunks.addAll(positions);
        done = 0;
        total = chunks.size();
        if (next != Phase.VERIFY) { metrics = new ResourceMetrics(); index.clear(); }
        progress = label() + " 0/" + total;
        plugin.getLogger().info("地图筛选阶段：" + phase + "，seed=" + world.getSeed() + "，区块=" + total);
        pump();
    }
    private String label() {
        return switch (phase) { case TERRAIN -> "地表预筛"; case RESOURCES -> "野外资源检查"; case VERIFY -> "改动区块复查"; };
    }
    private void pump() {
        if (closed) return;
        scans.removeIf(Future::isDone);
        while (running < concurrency && !chunks.isEmpty()) {
            ChunkPosition position = chunks.removeFirst();
            running++;
            try {
                world.getChunkAtAsync(position.x(), position.z(), phase == Phase.TERRAIN).whenComplete((chunk, failure) -> main(() -> {
                    if (failure != null || chunk == null) { fail("区块读取或生成失败：" + position, failure); return; }
                    scanChunk(chunk, position);
                }));
            } catch (RuntimeException exception) { fail("提交区块生成失败：" + position, exception); return; }
        }
        if (running == 0 && chunks.isEmpty()) scanned();
    }
    private void scanChunk(Chunk chunk, ChunkPosition position) {
        if (closed) return;
        try {
            boolean ownedTicket = chunk.addPluginChunkTicket(plugin);
            org.bukkit.ChunkSnapshot snapshot;
            try { snapshot = chunk.getChunkSnapshot(true, phase != Phase.TERRAIN, false, false); }
            finally { if (ownedTicket) chunk.removePluginChunkTicket(plugin); }
            int minY = world.getMinHeight(), maxY = world.getMaxHeight();
            Phase capturedPhase = phase;
            List<TerritoryStructure> structures = new ArrayList<>();
            if (capturedPhase != Phase.TERRAIN) for (var generated : chunk.getStructures()) {
                var box = generated.getBoundingBox();
                var key = java.util.Objects.requireNonNull(structureRegistry.getKey(generated.getStructure()), "自然结构未注册");
                if (key.toString().equals("wallwar:tutoriel")) continue;
                structures.addAll(TerritoryStructure.natural(key.toString(),
                        (int) Math.floor(box.getMinX()), (int) Math.floor(box.getMinY()), (int) Math.floor(box.getMinZ()),
                        (int) Math.ceil(box.getMaxX()) - 1, (int) Math.ceil(box.getMaxY()) - 1, (int) Math.ceil(box.getMaxZ()) - 1));
            }
            // Only detached, thread-safe snapshot data goes to the worker. Tickets are already released.
            Future<?> scan = worker.submit(() -> {
                try {
                    ResourceMetrics result = capturedPhase == Phase.TERRAIN
                            ? SnapshotScanner.scanTerrain(snapshot, ArenaGeometry.RADIUS, minY, maxY)
                            : SnapshotScanner.scan(snapshot, ArenaGeometry.RADIUS, minY, maxY, centerY + 2);
                    structures.forEach(result::structure);
                    main(() -> {
                        if (capturedPhase != phase) return;
                        if (phase == Phase.VERIFY) index.replace(position, result);
                        else {
                            metrics.merge(result);
                            if (phase == Phase.RESOURCES) index.replace(position, result);
                        }
                        running--;
                        done++;
                        switch (phase) { case TERRAIN -> terrainChunks++; case RESOURCES -> resourceChunks++; case VERIFY -> verifiedChunks++; }
                        progress = label() + " " + done + "/" + total;
                        if (phase == Phase.TERRAIN) {
                            String impossible = planner.impossibleTerrain(metrics, ArenaGeometry.columnsPerTeam(ArenaGeometry.RADIUS));
                            if (impossible != null) { reject(impossible); return; }
                        }
                        pump();
                    });
                } catch (RuntimeException exception) { main(() -> fail("区块资源扫描失败：" + position, exception)); }
            });
            scans.add(scan);
        } catch (RuntimeException exception) { fail("区块快照读取失败：" + position, exception); }
    }
    private void scanned() {
        if (phase == Phase.TERRAIN) {
            String rejected = planner.terrainRejection(metrics);
            if (rejected != null) reject(rejected);
            else scan(Phase.RESOURCES, ArenaGeometry.scanOrder());
            return;
        }
        if (phase == Phase.VERIFY) {
            metrics = index.totals();
            for (OriginalRuin.Placed ruin : placedRuins) {
                if (!OriginalRuin.verify(world, ruin)) { fail("原创补偿遗迹复查失败，未计入虚拟奖励分", null); return; }
                metrics.structure(ruin.metric());
            }
            ResourceMetrics measured = metrics;
            progress = "计算领地积分与陆地连通性";
            try {
                scans.add(worker.submit(() -> {
                    try {
                        TerritoryScoring.Report report = TerritoryScoring.evaluate(measured, scoreSettings);
                        main(() -> balance(report));
                    } catch (RuntimeException exception) { main(() -> fail("领地积分计算失败", exception)); }
                }));
            } catch (RuntimeException exception) { fail("积分任务调度失败", exception); }
            return;
        }
        prepareStructures();
    }
    private void balance(TerritoryScoring.Report report) {
        // One immutable copy per round, not one allocation per ore-placement attempt.
        protectedStructures = List.copyOf(metrics.structures());
        finalScores = report;
        if (initialScores == null) initialScores = report;
        targetScore = planner.target(report, targetScore);
        plugin.getLogger().info("领地积分：median=" + report.median() + "，target=" + targetScore + "，teams=" + report.teams());
        String terrain = planner.terrainRejection(metrics);
        if (terrain != null) { reject(terrain); return; }
        if (planner.balanced(metrics, report)) { finish(true, false, "四队领地积分与最低生存资源检查通过"); return; }
        if (compensationRounds >= scoreSettings.maximumRounds()) { reject("领地积分在补偿轮次上限内未收敛"); return; }
        dirty.clear();
        try {
            boolean needsForest = false;
            for (CombatTeam team : CombatTeam.values()) {
                nearResources(team);
                var plan = planner.forest(metrics, team, budgets.get(team), report);
                if (plan.forestLogs() > 0) {
                    needsForest = true; reserve(team, plan);
                    for (int remaining = plan.forestLogs(); remaining > 0; remaining -= 4) {
                        int logs = Math.min(4, remaining);
                        changes.add(() -> forestTree(team, logs));
                    }
                }
            }
            // Do not spend on ruins/ores before the planted forest has been measured.
            if (!needsForest) queueCompensation(report);
            if (changes.isEmpty()) { reject("积分或最低资源未达标，且没有可执行的补偿计划"); return; }
            compensationRounds++;
            runEdits();
        } catch (IllegalStateException exception) { reject(exception.getMessage()); }
    }
    private void prepareStructures() {
        progress = "规划中央结构与资源";
        try {
            CentralStructures.plan(world, centerY, plugin.getConfig().getBoolean("map.central-village", true),
                    plugin.getConfig().getBoolean("map.central-ancient-city", true), random, changes::add,
                    (minX, minZ, maxX, maxZ) -> dirty.addAll(ArenaGeometry.chunksIn(minX, minZ, maxX, maxZ)));
            for (CombatTeam team : CombatTeam.values()) spawnPad(team);
            runEdits();
        } catch (RuntimeException exception) { fail("中央结构规划失败", exception); }
    }
    private void queueCompensation(TerritoryScoring.Report report) {
        for (CombatTeam team : CombatTeam.values()) {
            var plan = planner.compensate(metrics, team, report.teams().get(team).total(), targetScore, budgets.get(team));
            reserve(team, plan);
            for (String kind : plan.ruins()) changes.add(() -> ruin(team, kind));
            for (Resource resource : Resource.values()) {
                int count = plan.ores().getOrDefault(resource, 0);
                for (int i = 0; i < count; i++) changes.add(() -> ore(team, resource));
            }
        }
    }
    private void reserve(CombatTeam team, TerritoryBalancePlanner.TeamPlan plan) {
        var previous = budgets.get(team);
        EnumMap<Resource, Integer> ores = new EnumMap<>(Resource.class); ores.putAll(previous.ores());
        plan.ores().forEach((resource, count) -> ores.merge(resource, count, Integer::sum));
        budgets.put(team, new TerritoryBalancePlanner.Budget(previous.forestLogs() + plan.forestLogs(), previous.ruins() + plan.ruins().size(), ores));
    }
    private void runEdits() {
        try {
            edits = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                if (closed) return;
                try {
                    long tickStart = System.nanoTime();
                    for (int i = 0; i < jobsPerTick && !changes.isEmpty(); i++) {
                        changes.removeFirst().run();
                        if (System.nanoTime() - tickStart >= editBudgetNanos) break;
                    }
                    progress = "布置资源，剩余 " + changes.size() + " 项";
                    if (changes.isEmpty()) {
                        edits.cancel();
                        scan(Phase.VERIFY, List.copyOf(dirty));
                    }
                } catch (UnsuitableTerrainException exception) { reject(exception.getMessage()); }
                catch (RuntimeException exception) { fail("资源布置失败", exception); }
            }, 1, 1);
        } catch (RuntimeException exception) { fail("资源任务调度失败", exception); }
    }
    private void changed(int minX, int minZ, int maxX, int maxZ) {
        dirty.addAll(ArenaGeometry.chunksIn(minX, minZ, maxX, maxZ));
    }
    private void nearResources(CombatTeam team) {
        // Place the pond first so it cannot erase the newly supplemented trees.
        if (metrics.nearbyWater(team) < ArenaGeometry.MIN_NEAR_WATER) changes.add(() -> pond(team));
        int missingWood = ArenaGeometry.missingWoodColumns(metrics.nearbyWood(team));
        for (int i = 0; i < missingWood; i++) changes.add(() -> tree(team, true));
    }
    private void spawnPad(CombatTeam team) {
        changes.add(() -> {
            changed(-5, -5, 5, 5);
            for (int x = 2; x <= 5; x++) for (int z = 2; z <= 5; z++) {
                world.getBlockAt(x * team.signX(), centerY, z * team.signZ()).setType(Material.GRASS_BLOCK, false);
                for (int y = 1; y <= 4; y++) world.getBlockAt(x * team.signX(), centerY + y, z * team.signZ()).setType(Material.AIR, false);
            }
        });
    }
    private void tree(CombatTeam team, boolean near) {
        tree(team, near, 4);
    }
    private void tree(CombatTeam team, boolean near, int logs) {
        for (int attempt = 0; attempt < 300; attempt++) {
            int x = team.signX() * (near ? 18 + random.nextInt(32) : 85 + random.nextInt(180));
            int z = team.signZ() * (near ? 18 + random.nextInt(32) : 85 + random.nextInt(180));
            if (plantTree(team, x, z, near, logs)) return;
        }
        throw new UnsuitableTerrainException("没有找到合适的补树位置");
    }
    private void forestTree(CombatTeam team, int logs) {
        for (int attempt = 0; attempt < 147; attempt++) {
            int cursor = groveCursor.getOrDefault(team, 0); groveCursor.put(team, cursor + 1);
            if (cursor % 49 == 0) groves.put(team, new ArenaGeometry.ChunkPosition(
                    team.signX() * (30 + random.nextInt(240)), team.signZ() * (30 + random.nextInt(240))));
            var grove = groves.get(team); int slot = cursor % 49;
            int x = grove.x() + (slot / 7 - 3) * 6, z = grove.z() + (slot % 7 - 3) * 6;
            if (plantTree(team, x, z, false, logs)) return;
        }
        throw new UnsuitableTerrainException("领地中未找到可种植补偿树林的位置");
    }
    private boolean plantTree(CombatTeam team, int x, int z, boolean near, int logs) {
        if (!near && Math.abs(x) <= ArenaGeometry.CENTRAL_RADIUS && Math.abs(z) <= ArenaGeometry.CENTRAL_RADIUS) return false;
        int y = surfaceY(x, z) + 1;
        if (protectedSite(x - 2, z - 2, x + 2, z + 2, y - 1, y + 5)) return false;
        if (y < 63 || y > 146 || !naturalGround(world.getBlockAt(x, y - 1, z).getType())) return false;
        if (near && (Math.abs(y - centerY) > 24 || !ArenaGeometry.withinSpawnRadius(x, y, z, team, centerY + 2))) return false;
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = 0; dy <= 5; dy++) {
            Material material = world.getBlockAt(x + dx, y + dy, z + dz).getType();
            if (!material.isAir() && material != Material.SNOW && (material.isSolid() || material == Material.WATER || material == Material.LAVA)) return false;
        }
        changed(x - 2, z - 2, x + 2, z + 2);
        world.getBlockAt(x, y - 1, z).setType(Material.DIRT, false);
        for (int dy = 0; dy < logs; dy++) world.getBlockAt(x, y + dy, z).setType(Material.OAK_LOG, false);
        Leaves leaves = (Leaves) Material.OAK_LEAVES.createBlockData(); leaves.setPersistent(true);
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = Math.max(0, logs - 2); dy <= logs; dy++) {
            if ((dx == 0 && dz == 0 && dy < logs) || Math.abs(dx) + Math.abs(dz) > 3) continue;
            world.getBlockAt(x + dx, y + dy, z + dz).setBlockData(leaves, false);
        }
        return true;
    }
    private int surfaceY(int x, int z) {
        int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        while (y > world.getMinHeight()) {
            Material material = world.getBlockAt(x, y, z).getType();
            if (material != Material.SNOW && (material.isSolid() || material == Material.WATER || material == Material.LAVA)) break;
            y--;
        }
        return y;
    }
    private static boolean naturalGround(Material material) {
        return material.name().endsWith("TERRACOTTA") || switch (material) {
            case GRASS_BLOCK, DIRT, COARSE_DIRT, ROOTED_DIRT, PODZOL, MYCELIUM, SAND, RED_SAND,
                    GRAVEL, STONE, ANDESITE, DIORITE, GRANITE, SNOW_BLOCK -> true;
            default -> false;
        };
    }
    private boolean protectedSite(int x1, int z1, int x2, int z2, int y1, int y2) {
        for (TerritoryStructure structure : protectedStructures) if (structure.overlaps(x1, z1, x2, z2, y1, y2, 3)) return true;
        for (OriginalRuin.Placed ruin : placedRuins) if (ruin.metric().overlaps(x1, z1, x2, z2, y1, y2, 3)) return true;
        return false;
    }
    private void ruin(CombatTeam team, String kind) {
        var template = java.util.Objects.requireNonNull(originalTemplates.get(kind), "原创遗迹模板未加载");
        var spec = template.spec();
        for (int attempt = 0; attempt < 160; attempt++) {
            int x = team.signX() * (12 + random.nextInt(268)) - spec.sizeX() / 2;
            int z = team.signZ() * (12 + random.nextInt(268)) - spec.sizeZ() / 2;
            int endX = x + spec.sizeX() - 1, endZ = z + spec.sizeZ() - 1;
            if (!OriginalRuinCatalog.fits(spec, team, x, z)) continue;
            int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE; boolean valid = true;
            for (int dx = 0; dx < spec.sizeX() && valid; dx++) for (int dz = 0; dz < spec.sizeZ(); dz++) {
                int ground = surfaceY(x + dx, z + dz);
                if (!naturalGround(world.getBlockAt(x + dx, ground, z + dz).getType())) { valid = false; break; }
                for (int dy = 1; dy <= spec.sizeY(); dy++) {
                    Material above = world.getBlockAt(x + dx, ground + dy, z + dz).getType();
                    if (!above.isAir() && above != Material.SNOW && (above.isSolid() || above == Material.WATER || above == Material.LAVA)) { valid = false; break; }
                }
                if (!valid) break;
                min = Math.min(min, ground); max = Math.max(max, ground);
            }
            if (!valid || min < 62 || max > 140 || max - min > 1) continue;
            if (protectedSite(x, z, endX, endZ, min - 1, max + spec.sizeY() - 1)) continue;
            var ruin = new OriginalRuin.Placed(team, x, max, z, template);
            changed(x, z, endX, endZ);
            List<Runnable> jobs = OriginalRuin.jobs(world, ruin, random, () -> placedRuins.add(ruin));
            for (int i = jobs.size() - 1; i >= 0; i--) changes.addFirst(jobs.get(i));
            return;
        }
        throw new UnsuitableTerrainException("领地中未找到合适的地表补偿遗迹位置");
    }
    private void pond(CombatTeam team) {
        for (int attempt = 0; attempt < 80; attempt++) {
            int x = team.signX() * (18 + random.nextInt(32)), z = team.signZ() * (18 + random.nextInt(32));
            int min = Integer.MAX_VALUE, y = Integer.MIN_VALUE; boolean valid = true;
            for (int dx = -2; dx <= 2 && valid; dx++) for (int dz = -2; dz <= 2; dz++) {
                int ground = surfaceY(x + dx, z + dz);
                if (!naturalGround(world.getBlockAt(x + dx, ground, z + dz).getType())) { valid = false; break; }
                min = Math.min(min, ground); y = Math.max(y, ground);
            }
            if (!valid || min < 62 || y > 140 || y - min > 1 || Math.abs(y - centerY) > 32
                    || !ArenaGeometry.withinSpawnRadius(x + team.signX(), y, z + team.signZ(), team, centerY + 2)) continue;
            if (protectedSite(x - 2, z - 2, x + 2, z + 2, min - 1, y + 3)) continue;
            changed(x - 2, z - 2, x + 2, z + 2);
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                world.getBlockAt(x + dx, y - 1, z + dz).setType(Math.abs(dx) < 2 && Math.abs(dz) < 2 ? Material.GLOWSTONE : Material.CLAY, false);
                world.getBlockAt(x + dx, y, z + dz).setType(Math.abs(dx) < 2 && Math.abs(dz) < 2 ? Material.WATER : Material.GRASS_BLOCK, false);
                for (int dy = 1; dy <= 3; dy++) world.getBlockAt(x + dx, y + dy, z + dz).setType(Material.AIR, false);
            }
            return;
        }
        throw new UnsuitableTerrainException("未找到不会覆盖遗迹的近点水池位置");
    }
    private void ore(CombatTeam team, Resource resource) {
        for (int attempt = 0; attempt < 400; attempt++) {
            int x = team.signX() * (8 + random.nextInt(280)), z = team.signZ() * (8 + random.nextInt(280));
            if (Math.abs(x) <= ArenaGeometry.CENTRAL_RADIUS && Math.abs(z) <= ArenaGeometry.CENTRAL_RADIUS) continue;
            int y = resource == Resource.DIAMOND ? -54 + random.nextInt(40) : -40 + random.nextInt(80);
            if (protectedSite(x, z, x, z, y, y)) continue;
            var block = world.getBlockAt(x, y, z);
            if (block.getType() != Material.STONE && block.getType() != Material.DEEPSLATE) continue;
            boolean deep = block.getType() == Material.DEEPSLATE;
            Material replacement = switch (resource) {
                case COAL -> deep ? Material.DEEPSLATE_COAL_ORE : Material.COAL_ORE;
                case IRON -> deep ? Material.DEEPSLATE_IRON_ORE : Material.IRON_ORE;
                case GOLD -> deep ? Material.DEEPSLATE_GOLD_ORE : Material.GOLD_ORE;
                case DIAMOND -> deep ? Material.DEEPSLATE_DIAMOND_ORE : Material.DIAMOND_ORE;
                default -> throw new IllegalArgumentException("不能将原木作为矿物布置");
            };
            changed(x, z, x, z);
            block.setType(replacement, false); return;
        }
        throw new UnsuitableTerrainException("没有找到合适的补矿位置");
    }

    private void main(Runnable action) {
        if (!closed && plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, () -> {
            if (closed) return;
            try { action.run(); }
            catch (RuntimeException exception) { fail("地图准备回调失败，保留现场", exception); }
        });
    }
    private void reject(String reason) { finish(false, true, reason); }
    private void fail(String reason, Throwable cause) {
        if (cause != null) plugin.getLogger().log(Level.SEVERE, reason, cause);
        finish(false, false, reason);
    }
    private void finish(boolean accepted, boolean retrySeed, String reason) {
        if (closed) return;
        String diagnostics = "terrain=" + terrainChunks + ", resources=" + resourceChunks + ", recheck=" + verifiedChunks
                + ", scoreRounds=" + compensationRounds + ", target=" + targetScore
                + ", elapsedMs=" + (System.nanoTime() - startedNanos) / 1_000_000;
        close();
        completion.accept(new Result(accepted, retrySeed, reason, centerY, metrics, diagnostics, initialScores, finalScores, targetScore));
    }
    @Override public void close() {
        closed = true;
        if (edits != null) edits.cancel();
        if (watchdog != null) watchdog.cancel();
        scans.forEach(scan -> scan.cancel(true));
        scans.clear();
        chunks.clear();
        changes.clear();
        dirty.clear();
        index.clear();
    }
}

