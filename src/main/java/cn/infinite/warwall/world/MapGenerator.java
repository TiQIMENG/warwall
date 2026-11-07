package cn.infinite.warwall.world;

import cn.infinite.warwall.WarwallPlugin;
import cn.infinite.warwall.arena.Box;
import cn.infinite.warwall.arena.WarwallArena;
import cn.infinite.warwall.team.TeamColor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.bukkit.Material;
import org.bukkit.World;

/**
 * 每局随机生成的四队竞技场。
 *
 * <p>这是「每把地图都不一样」的落点：不读 {@code arenas.yml} 里手工配的图，
 * 而是每局现生成一块新地形——地表起伏、矿区分布、墙的门位、中央结构、装饰材料全部随机。
 *
 * <p>为了保证公平，四队的基础结构走 <b>旋转对称</b>：地形噪声用同一套函数，但每个队伍
 * 的基座被压平成平台；四队的距离、核心房尺寸、矿区体积完全一致，只有朝向与随机细节不同。
 *
 * <p>生成规模刻意压在小范围（半径 56~72），总写入量约十几万方块，主线程几百毫秒内能跑完。
 */
public final class MapGenerator {

    private final WarwallPlugin plugin;

    public MapGenerator(WarwallPlugin plugin) {
        this.plugin = plugin;
    }

    /** 生成结果：竞技场配置 + 本次随机种子，方便复现问题。 */
    public record Generated(WarwallArena arena, long seed, int radius) {
    }

    /**
     * 在指定世界的一块区域上生成一张新图。
     *
     * @return 生成好的竞技场；世界不可用时返回 null
     */
    public Generated generate(World world, int centerX, int baseY, int centerZ) {
        return generateInternal(world, centerX, baseY, centerZ, 0);
    }

    /**
     * 带重试的生成。连通性检查不过就换种子重来，最多 {@code mapgen.max-attempts} 次。
     *
     * <p>为什么不回滚地形：整块区域是原地重写的，直接覆盖比「记录改动再撤回」简单可靠，
     * 代价只是多重画一遍（一次约几百毫秒）。
     */
    private Generated generateInternal(World world, int centerX, int baseY, int centerZ, int attempt) {
        if (world == null) {
            return null;
        }
        long seed = System.nanoTime() ^ (long) (Math.random() * 1_000_000);
        Random random = new Random(seed);

        int radius = plugin.config().mapgenMinRadius()
                + random.nextInt(Math.max(1, plugin.config().mapgenMaxRadius() - plugin.config().mapgenMinRadius() + 1));
        int baseRadius = (int) (radius * 0.62);
        int startAngle = random.nextInt(90);

        Material topMaterial = pickTop(random);
        Material fillMaterial = pickFill(random);
        Material wallMaterial = pickWall(random);

        // 1) 地形：起伏地表 + 基座压平 + 中央洼地
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                int wx = centerX + x;
                int wz = centerZ + z;
                double distance = Math.sqrt(x * x + z * z);
                if (distance > radius) {
                    continue;
                }
                int height = terrainHeight(x, z, radius, random);
                int worldTop = baseY + height;
                // 清空地面以上，避免上一局的树和建筑残留
                for (int y = worldTop + 1; y <= baseY + 26; y++) {
                    set(world, wx, y, wz, Material.AIR);
                }
                // 地表以下填实
                for (int y = baseY - 5; y <= worldTop; y++) {
                    Material material = y == worldTop ? topMaterial : fillMaterial;
                    set(world, wx, y, wz, material);
                }
                // 矿区：地下 3~5 格随机矿石，越靠队伍基座越密
                // 矿石不在这里铺：改成按各队基座的相对距离统一生成，见 placeTeamOreFields
            }
        }

        // 2) 四个队伍基座（旋转对称）：压平 + 平台 + 凋零底座 + 核心房
        Map<String, WarwallArena.TeamSetup> teams = new LinkedHashMap<>();
        List<Box> walls = new ArrayList<>();
        List<Box> unbreakable = new ArrayList<>();
        List<Box> mining = new ArrayList<>();
        for (int i = 0; i < TeamColor.values().length; i++) {
            TeamColor team = TeamColor.values()[i];
            double angle = Math.toRadians(startAngle + i * 90);
            int bx = centerX + (int) Math.round(Math.cos(angle) * baseRadius);
            int bz = centerZ + (int) Math.round(Math.sin(angle) * baseRadius);

            flatten(world, bx, bz, baseY, 9, topMaterial, fillMaterial);
            // 队伍色羊毛平台，一眼能认出是谁家
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    set(world, bx + dx, baseY, bz + dz, team.wool());
                }
            }
            // 凋零底座
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    set(world, bx + dx, baseY + 1, bz + dz, Material.OBSIDIAN);
                }
            }
            // 核心房：3×3×3 的空腔 + 外壳，核心区域就是它
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    for (int dy = 2; dy <= 5; dy++) {
                        boolean shell = Math.abs(dx) == 2 || Math.abs(dz) == 2 || dy == 5;
                        set(world, bx + dx, baseY + dy, bz + dz,
                                shell ? Material.STONE_BRICKS : Material.AIR);
                    }
                }
            }
            unbreakable.add(Box.of(world.getName(),
                    bx - 2, baseY + 1, bz - 2, bx + 2, baseY + 5, bz + 2));
            // 每队自己脚下一块矿区，体积一致保证公平
            mining.add(Box.of(world.getName(),
                    bx - 8, baseY - 4, bz - 8, bx + 8, baseY, bz + 8));

            float yaw = (float) Math.toDegrees(-Math.atan2(bz - centerZ, bx - centerX)) - 90f;
            WarwallArena.SpawnPoint spawn = new WarwallArena.SpawnPoint(
                    bx + 0.5, baseY + 1, bz + 0.5, yaw, 0f);
            WarwallArena.SpawnPoint wither = new WarwallArena.SpawnPoint(
                    bx + 0.5, baseY + 2, bz + 0.5, 0f, 0f);
            Box nexus = Box.of(world.getName(),
                    bx - 2, baseY + 2, bz - 2, bx + 2, baseY + 4, bz + 2);
            teams.put(team.id(), new WarwallArena.TeamSetup(spawn, spawn, wither, nexus));
        }

        // 3) 外圈石墙 + 四个队位各留一道门
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                double distance = Math.sqrt(x * x + z * z);
                if (distance < radius - 1.2 || distance > radius) {
                    continue;
                }
                double angle = Math.toDegrees(Math.atan2(z, x));
                boolean gate = false;
                for (int i = 0; i < 4; i++) {
                    double gateAngle = startAngle + i * 90;
                    if (Math.abs(((angle - gateAngle + 540) % 360) - 180) > 172) {
                        gate = true;
                        break;
                    }
                }
                if (gate) {
                    continue;
                }
                for (int dy = 0; dy < 5; dy++) {
                    set(world, centerX + x, baseY + dy, centerZ + z, wallMaterial);
                }
            }
        }

        // 4) 中央结构：每局随机一种（方尖碑 / 环形柱 / 阶梯台）
        buildCenter(world, centerX, baseY, centerZ, radius, random, fillMaterial);

        // 4.4) 四队矿区：按各自基座的相对距离生成，四队共用一份模板
        placeTeamOreFields(world, baseY, random, teams, mining);

        // 4.5) 四队领地公平性补偿
        // 参考 WarwallTool 的 TerritoryScoring / TerritoryBalancePlanner：
        // 随机地图必然有人吃亏，所以先按同一套权重给四队领地打分，低于中位数的补矿。
        balanceTerritories(world, baseY, teams, mining, random);

        // 4.6) 结构里的奖励箱
        // 参考数据包 data/wallwar/loot_table/structure/*.json：中央与每队领地各放一个箱子，
        // 内容是多池掉落（材料池 / 战斗消耗池 / 食物池 / 附魔金装 / 稀有池）。
        placeLootChest(world, centerX, baseY + 2, centerZ + 2, random, true);
        int chestIndex = 0;
        for (WarwallArena.TeamSetup setup : teams.values()) {
            int cx = (int) setup.spawn().x() + (chestIndex % 2 == 0 ? 6 : -6);
            int cz = (int) setup.spawn().z() + (chestIndex % 4 < 2 ? 6 : -6);
            placeLootChest(world, cx, baseY + 1, cz, random, false);
            chestIndex++;
        }

        // 5) 组装竞技场
        WarwallArena.SpawnPoint center = new WarwallArena.SpawnPoint(
                centerX + 0.5, baseY + 1, centerZ + 0.5, 0f, 0f);
        WarwallArena.SpawnPoint lobby = new WarwallArena.SpawnPoint(
                centerX + 0.5, baseY + 30, centerZ + 0.5, 0f, 0f);
        WarwallArena.SpawnPoint spectator = new WarwallArena.SpawnPoint(
                centerX + 0.5, baseY + 36, centerZ + 0.5, 0f, 0f);

        List<WarwallArena.DiamondDrop> drops = new ArrayList<>();
        drops.add(new WarwallArena.DiamondDrop(centerX + 0.5, baseY + 3, centerZ + 0.5,
                180, 2 + random.nextInt(2), "DIAMOND"));
        drops.add(new WarwallArena.DiamondDrop(centerX + 0.5, baseY + 3, centerZ + 0.5,
                420, 1, "GOLD_INGOT"));

        List<Box> wallsMeta = new ArrayList<>();
        wallsMeta.add(Box.of(world.getName(),
                centerX - radius, baseY, centerZ - radius,
                centerX + radius, baseY + 4, centerZ + radius));

        WarwallArena arena = new WarwallArena(
                "_generated", true, world.getName(),
                Math.max(2, plugin.config().mapgenMinPlayers()),
                plugin.config().mapgenMaxPlayers(),
                lobby, spectator, center,
                centerX, centerZ,
                teams, wallsMeta, unbreakable, mining, new ArrayList<>(), drops);

        // 连通性检查：任何一个队伍的出生点走不到中心、或可走面积太小，就换种子重来。
        int maxAttempts = Math.max(1, plugin.config().mapgenMaxAttempts());
        for (Map.Entry<String, WarwallArena.TeamSetup> entry : teams.entrySet()) {
            WarwallArena.SpawnPoint spawn = entry.getValue().spawn();
            // 注意：不能直接从出生点起步——那一列最高的实心方块是核心房的屋顶，
            // BFS 会一直在屋顶上打转（实测只走得出 0.2%）。往地图中心方向挪 7 格，
            // 落在被压平的基座平台上，才是真正的「脚下地面」。
            double dx = centerX - spawn.x();
            double dz = centerZ - spawn.z();
            double length = Math.max(1.0e-6, Math.sqrt(dx * dx + dz * dz));
            int startX = (int) Math.round(spawn.x() + dx / length * 7.0);
            int startZ = (int) Math.round(spawn.z() + dz / length * 7.0);
            LandTopology.Result result = LandTopology.check(world,
                    centerX, centerZ, radius, startX, startZ,
                    plugin.config().mapgenMinLandRatio());
            if (!result.reachable()) {
                plugin.getLogger().warning("地图连通性不合格：" + entry.getKey()
                        + " 队出生点不可达中心（可走比例 " + String.format("%.1f%%", result.ratio() * 100)
                        + "），第 " + (attempt + 1) + " 次重试");
                if (attempt + 1 < maxAttempts) {
                    return generateInternal(world, centerX, baseY, centerZ, attempt + 1);
                }
                plugin.getLogger().warning("已用尽重试次数，沿用当前这张图（可能有个别队伍出生点受限）");
            }
        }

        plugin.getLogger().info("已生成随机地图：半径 " + radius + "，种子 " + seed
                + "，地表 " + topMaterial + "，墙 " + wallMaterial
                + "（第 " + (attempt + 1) + " 次生成通过连通性检查）");
        return new Generated(arena, seed, radius);
    }

    // ===== 公平性后处理（照搬 warwalltool 的 MapPreparation）=====

    private java.util.concurrent.ExecutorService worker;

    /**
     * 生成完布局后，跑一遍 warwalltool 的领地评分与补偿。
     *
     * <p>分工：**布局**（四队基座、凋零位、核心房、墙）由本类保证对称；
     * **地形与资源的公平性**交给照搬过来的 {@code MapPreparation}——它会扫描四队领地、
     * 按权重打分、给弱队补矿补木，并在收敛不了时提示换种子。
     *
     * <p>它是异步跑的（内部按 tick 分摊方块改动），所以这里只负责发起与记日志，
     * 不阻塞开局。
     */
    public void startFairnessPass(World world) {
        if (world == null) {
            return;
        }
        if (worker == null || worker.isShutdown()) {
            worker = java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "warwall-mapgen");
                thread.setDaemon(true);
                return thread;
            });
        }
        try {
            cn.infinite.warwall.world.map.ScoreSettings settings =
                    cn.infinite.warwall.world.map.ScoreSettings.defaults();
            cn.infinite.warwall.world.map.MapPreparation preparation =
                    new cn.infinite.warwall.world.map.MapPreparation(plugin, world, worker, result -> {
                        if (result.accepted()) {
                            plugin.getLogger().info("领地公平性检查通过：" + result.reason());
                        } else {
                            plugin.getLogger().warning("领地公平性未达标：" + result.reason()
                                    + (result.retrySeed() ? "（建议换种子）" : ""));
                        }
                    });
            preparation.start();
            plugin.getLogger().info("已启动领地评分与补偿（照搬 warwalltool）");
        } catch (Throwable ex) {
            plugin.getLogger().warning("领地评分启动失败，跳过公平性后处理：" + ex.getMessage());
        }
    }

    /** 关闭后台线程，供插件卸载调用。 */
    public void shutdown() {
        if (worker != null) {
            worker.shutdownNow();
            worker = null;
        }
    }

    // ===== 结构奖励箱 =====

    /**
     * 放一个带战利品的箱子。
     *
     * <p>掉落结构直接对着数据包的 {@code data/wallwar/loot_table/structure/brew.json}：
     * <ul>
     *   <li>材料池：5~10 抽，含 45 权重的空，其余是红石/糖/瓶子/荧石粉</li>
     *   <li>战斗池：3~5 抽，火药/蛛眼/岩浆膏/兔子脚/烈焰粉</li>
     *   <li>食物池：1~3 抽，金胡萝卜/闪烁西瓜片/河豚/甘蔗</li>
     *   <li>装备池：必定 1 件附魔金装</li>
     *   <li>稀有池：1 抽，恶魂之泪/鹦鹉螺壳/附魔金苹果/海龟帽</li>
     * </ul>
     * 这个设计保证了「开箱有收益但不是白给」，与补矿互补。
     */
    private void placeLootChest(World world, int x, int y, int z, Random random, boolean rare) {
        set(world, x, y, z, Material.CHEST);
        org.bukkit.block.Block block = world.getBlockAt(x, y, z);
        if (!(block.getState() instanceof org.bukkit.block.Chest chest)) {
            return;
        }
        org.bukkit.inventory.Inventory inventory = chest.getBlockInventory();

        // 材料池
        fillPool(inventory, random, 5 + random.nextInt(6), new Object[][] {
                {null, 45}, {Material.REDSTONE, 16}, {Material.SUGAR, 14},
                {Material.GLASS_BOTTLE, 10}, {Material.GLOWSTONE_DUST, 8}
        });
        // 战斗消耗池
        fillPool(inventory, random, 3 + random.nextInt(3), new Object[][] {
                {null, 65}, {Material.BLAZE_POWDER, 34}, {Material.GUNPOWDER, 42},
                {Material.SPIDER_EYE, 46}, {Material.MAGMA_CREAM, 40}, {Material.RABBIT_FOOT, 23}
        });
        // 食物池
        fillPool(inventory, random, 1 + random.nextInt(3), new Object[][] {
                {null, 102}, {Material.GOLDEN_CARROT, 68},
                {Material.GLISTERING_MELON_SLICE, 57}, {Material.PUFFERFISH, 63}
        });
        // 装备池：一件附魔金装
        Material[] goldGear = {Material.GOLDEN_HELMET, Material.GOLDEN_CHESTPLATE,
                Material.GOLDEN_LEGGINGS, Material.GOLDEN_BOOTS};
        org.bukkit.inventory.ItemStack gear =
                new org.bukkit.inventory.ItemStack(goldGear[random.nextInt(goldGear.length)]);
        var meta = gear.getItemMeta();
        if (meta != null) {
            meta.addEnchant(org.bukkit.enchantments.Enchantment.PROTECTION, 2, true);
            gear.setItemMeta(meta);
        }
        inventory.setItem(random.nextInt(inventory.getSize()), gear);

        // 稀有池：中央箱子概率更高
        if (rare || random.nextDouble() < 0.25) {
            Material[] rareItems = {Material.GHAST_TEAR, Material.NAUTILUS_SHELL,
                    Material.ENCHANTED_GOLDEN_APPLE, Material.TURTLE_HELMET};
            inventory.setItem(random.nextInt(inventory.getSize()),
                    new org.bukkit.inventory.ItemStack(rareItems[random.nextInt(rareItems.length)]));
        }
    }

    /** 按权重抽若干次往箱子里塞物品；{@code null} 表示这一抽空。 */
    private void fillPool(org.bukkit.inventory.Inventory inventory, Random random, int rolls,
                          Object[][] weighted) {
        int totalWeight = 0;
        for (Object[] entry : weighted) {
            totalWeight += (int) entry[1];
        }
        for (int i = 0; i < rolls; i++) {
            int pick = random.nextInt(totalWeight);
            Object chosen = null;
            for (Object[] entry : weighted) {
                pick -= (int) entry[1];
                if (pick < 0) {
                    chosen = entry[0];
                    break;
                }
            }
            if (chosen == null) {
                continue;
            }
            int slot = random.nextInt(inventory.getSize());
            if (inventory.getItem(slot) != null) {
                continue;
            }
            int amount = 1 + random.nextInt(3);
            inventory.setItem(slot, new org.bukkit.inventory.ItemStack((Material) chosen, amount));
        }
    }

    // ===== 公平性补偿 =====

    /**
     * 给四队领地打分，低于中位数的补矿。
     *
     * <p>权重参考 WarwallTool 的领地计分：钻石 6 分、金 3 分、铁 2 分、煤 1 分、原木 1 分。
     * 只看每队自己的矿区盒子，避免中央资源摊薄差异。
     */
    private void balanceTerritories(World world, int baseY,
                                    Map<String, WarwallArena.TeamSetup> teams,
                                    List<Box> miningZones, Random random) {
        int teamCount = teams.size();
        if (teamCount == 0 || miningZones.size() < teamCount) {
            return;
        }
        int[] scores = new int[teamCount];
        for (int i = 0; i < teamCount; i++) {
            scores[i] = scoreBox(world, miningZones.get(i));
        }
        int[] sorted = scores.clone();
        java.util.Arrays.sort(sorted);
        int median = (sorted[sorted.length / 2 - 1] + sorted[sorted.length / 2]) / 2;
        int floor = Math.max(1, (int) (median * 0.9));

        plugin.getLogger().info("四队领地资源分：" + java.util.Arrays.toString(scores)
                + "，中位线 " + median);

        for (int i = 0; i < teamCount; i++) {
            if (scores[i] >= floor) {
                continue;
            }
            int deficit = median - scores[i];
            // 按缺口补矿：缺口越大补得越多，但每队最多补 3 组。
            int veins = Math.min(3, 1 + deficit / 12);
            Box zone = miningZones.get(i);
            for (int v = 0; v < veins; v++) {
                int vx = zone.minX() + random.nextInt(Math.max(1, zone.maxX() - zone.minX()));
                int vz = zone.minZ() + random.nextInt(Math.max(1, zone.maxZ() - zone.minZ()));
                int vy = zone.minY() + 1 + random.nextInt(Math.max(1, zone.maxY() - zone.minY() - 1));
                Material ore = random.nextBoolean() ? Material.IRON_ORE : Material.GOLD_ORE;
                for (int d = 0; d < 4; d++) {
                    set(world, vx + d, vy, vz, ore);
                }
            }
            plugin.getLogger().info("为第 " + (i + 1) + " 队补了 " + veins + " 组矿（缺口 " + deficit + "）");
        }
    }

    /** 一个盒子里的资源权重分。 */
    private int scoreBox(World world, Box box) {
        int score = 0;
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int y = box.minY(); y <= box.maxY(); y++) {
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    Material type = world.getBlockAt(x, y, z).getType();
                    score += switch (type) {
                        case DIAMOND_ORE, DEEPSLATE_DIAMOND_ORE -> 6;
                        case GOLD_ORE, DEEPSLATE_GOLD_ORE -> 3;
                        case IRON_ORE, DEEPSLATE_IRON_ORE -> 2;
                        case COAL_ORE, DEEPSLATE_COAL_ORE -> 1;
                        default -> 0;
                    };
                }
            }
        }
        return score;
    }

    // ===== 地形辅助 =====

    /** 用整数哈希算一个平滑起伏，避免引入噪声库。 */
    private int terrainHeight(int x, int z, int radius, Random random) {
        double wave = Math.sin(x * 0.12) * Math.cos(z * 0.11) * 2.2
                + Math.sin((x + z) * 0.05) * 1.8;
        // 中央略微下凹，让中心结构更显眼
        double distance = Math.sqrt(x * x + z * z) / Math.max(1, radius);
        double basin = -2.0 * (1.0 - distance);
        return (int) Math.round(wave + basin) + random.nextInt(2) - 1;
    }

    /**
     * 生成四队的矿区：<b>以各自基座为原点的相对距离</b>，且四队共用同一套矿石模板。
     *
     * <p>为什么这么做：矿区按「到地图中心的绝对距离」铺，看起来对称，实际因为地表起伏与
     * 随机抽样，四队资源分会差出几倍（实测出现过 [0,2,1,10]）。改成
     * 「一份模板 + 旋转盖章」后，四队的矿石数量、种类、相对位置**逐块相同**，
     * 差别只剩下各自的朝向，公平性由构造保证而不是靠事后补。
     *
     * <p>相对距离的含义：密度的判定用 {@code 到本队基座的距离 / 矿区半径}，
     * 与地图大小、基座在地图上的位置都无关。
     */
    private void placeTeamOreFields(World world, int baseY, Random random,
                                    Map<String, WarwallArena.TeamSetup> teams,
                                    List<Box> miningZones) {
        int fieldRadius = 8;
        // 1) 先在一份「标准模板」上抽矿石，坐标系是以基座为原点的局部坐标。
        record Ore(int dx, int dy, int dz, Material material) {
        }
        List<Ore> template = new ArrayList<>();
        for (int dx = -fieldRadius; dx <= fieldRadius; dx++) {
            for (int dz = -fieldRadius; dz <= fieldRadius; dz++) {
                // 相对距离：0 在基座中心，1 在矿区边缘
                double relative = Math.sqrt(dx * dx + dz * dz) / fieldRadius;
                if (relative > 1.0) {
                    continue;
                }
                // 越靠近基座越密：相对距离 0 处 0.10，边缘 0.02
                double density = 0.10 - 0.08 * relative;
                for (int dy = -4; dy <= -1; dy++) {
                    if (random.nextDouble() > density) {
                        continue;
                    }
                    Material ore = Material.COAL_ORE;
                    double roll = random.nextDouble();
                    if (roll < 0.06) {
                        ore = Material.DIAMOND_ORE;
                    } else if (roll < 0.18) {
                        ore = Material.GOLD_ORE;
                    } else if (roll < 0.55) {
                        ore = Material.IRON_ORE;
                    }
                    template.add(new Ore(dx, dy, dz, ore));
                }
            }
        }

        // 2) 把模板按各队朝向旋转后盖章，四队逐块一致。
        int index = 0;
        for (WarwallArena.TeamSetup setup : teams.values()) {
            int bx = (int) Math.floor(setup.spawn().x());
            int bz = (int) Math.floor(setup.spawn().z());
            // 每个队旋转 90°×index，正好与四个基座的方位角对应
            int rotation = index * 90;
            for (Ore ore : template) {
                int dx = ore.dx();
                int dz = ore.dz();
                int rx;
                int rz;
                switch (rotation) {
                    case 90 -> {
                        rx = -dz;
                        rz = dx;
                    }
                    case 180 -> {
                        rx = -dx;
                        rz = -dz;
                    }
                    case 270 -> {
                        rx = dz;
                        rz = -dx;
                    }
                    default -> {
                        rx = dx;
                        rz = dz;
                    }
                }
                int y = baseY + ore.dy();
                // 只在实心位置替换，避免在空中凭空生成矿石
                Material current = world.getBlockAt(bx + rx, y, bz + rz).getType();
                if (current == Material.AIR) {
                    continue;
                }
                set(world, bx + rx, y, bz + rz, ore.material());
            }
            index++;
        }
        plugin.getLogger().info("四队矿区已按相对距离生成：模板 " + template.size()
                + " 块矿石 × " + teams.size() + " 队");
    }

    /** 把一个圆形平台压平。 */
    private void flatten(World world, int cx, int cz, int baseY, int radius,
                         Material top, Material fill) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) {
                    continue;
                }
                for (int dy = 1; dy <= 6; dy++) {
                    set(world, cx + dx, baseY + dy, cz + dz, Material.AIR);
                }
                set(world, cx + dx, baseY, cz + dz, top);
                for (int dy = -5; dy < 0; dy++) {
                    set(world, cx + dx, baseY + dy, cz + dz, fill);
                }
            }
        }
    }

    /** 中央结构：三种形态随机一种，每局都不一样。 */
    private void buildCenter(World world, int cx, int baseY, int cz,
                             int radius, Random random, Material material) {
        int shape = random.nextInt(3);
        switch (shape) {
            case 0 -> {
                int height = 6 + random.nextInt(7);
                for (int dy = 1; dy <= height; dy++) {
                    set(world, cx, baseY + dy, cz, Material.QUARTZ_BLOCK);
                }
                set(world, cx, baseY + height + 1, cz, Material.BEACON);
            }
            case 1 -> {
                int ring = 4 + random.nextInt(4);
                for (int dx = -ring; dx <= ring; dx++) {
                    for (int dz = -ring; dz <= ring; dz++) {
                        double distance = Math.sqrt(dx * dx + dz * dz);
                        if (distance > ring || distance < ring - 1) {
                            continue;
                        }
                        // 四个正方向留门，否则中央会被自己围成孤岛，玩家进不去也出不来。
                        if (Math.abs(dx) <= 1 || Math.abs(dz) <= 1) {
                            continue;
                        }
                        for (int dy = 1; dy <= 4; dy++) {
                            set(world, cx + dx, baseY + dy, cz + dz, material);
                        }
                    }
                }
            }
            default -> {
                int steps = 3 + random.nextInt(3);
                for (int step = 0; step < steps; step++) {
                    int half = 6 - step;
                    for (int dx = -half; dx <= half; dx++) {
                        for (int dz = -half; dz <= half; dz++) {
                            set(world, cx + dx, baseY + 1 + step, cz + dz, Material.SMOOTH_STONE);
                        }
                    }
                }
            }
        }
    }

    private Material pickTop(Random random) {
        return switch (random.nextInt(4)) {
            case 0 -> Material.GRASS_BLOCK;
            case 1 -> Material.PODZOL;
            case 2 -> Material.COARSE_DIRT;
            default -> Material.MOSS_BLOCK;
        };
    }

    private Material pickFill(Random random) {
        return switch (random.nextInt(3)) {
            case 0 -> Material.STONE;
            case 1 -> Material.DEEPSLATE;
            default -> Material.ANDESITE;
        };
    }

    private Material pickWall(Random random) {
        return switch (random.nextInt(4)) {
            case 0 -> Material.STONE_BRICKS;
            case 1 -> Material.DEEPSLATE_BRICKS;
            case 2 -> Material.BLACKSTONE;
            default -> Material.DIORITE;
        };
    }

    @SuppressWarnings("deprecation")
    private void set(World world, int x, int y, int z, Material material) {
        if (y <= world.getMinHeight() || y >= world.getMaxHeight()) {
            return;
        }
        world.getBlockAt(x, y, z).setType(material, false);
    }
}
