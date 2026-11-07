package cn.infinite.warwall;

import cn.infinite.warwall.arena.ArenaPool;
import cn.infinite.warwall.arena.ArenaStore;
import cn.infinite.warwall.arena.Box;
import cn.infinite.warwall.arena.WarwallArena;
import cn.infinite.warwall.boss.BossManager;
import cn.infinite.warwall.building.BuildingManager;
import cn.infinite.warwall.combat.CombatListener;
import cn.infinite.warwall.combat.CombatManager;
import cn.infinite.warwall.command.WarwallCommand;
import cn.infinite.warwall.config.WarwallConfig;
import cn.infinite.warwall.core.MatchState;
import cn.infinite.warwall.economy.Economy;
import cn.infinite.warwall.game.BorderManager;
import cn.infinite.warwall.game.LootManager;
import cn.infinite.warwall.game.MatchController;
import cn.infinite.warwall.game.TeamManager;
import cn.infinite.warwall.game.WallManager;
import cn.infinite.warwall.glow.TeamGlowService;
import cn.infinite.warwall.item.CustomItems;
import cn.infinite.warwall.kit.KitManager;
import cn.infinite.warwall.listener.BlockListener;
import cn.infinite.warwall.listener.DeathListener;
import cn.infinite.warwall.listener.GameListener;
import cn.infinite.warwall.nexus.NexusManager;
import cn.infinite.warwall.stats.StatsManager;
import cn.infinite.warwall.team.TeamColor;
import cn.infinite.warwall.ui.UiManager;
import cn.infinite.warwall.wither.WitherManager;
import cn.infinite.warwall.world.MapGenerator;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 插件主类：只负责装配与生命周期，不写玩法。
 *
 * <p>装配顺序有讲究——配置先就绪，竞技场读取依赖配置里的限制值，命令注册依赖前三者。
 */
public final class WarwallPlugin extends JavaPlugin {

    private WarwallConfig config;
    private ArenaStore arenaStore;
    private MatchController controller;
    private WarwallCommand command;

    // 各功能管理器；装配顺序见 onEnable，越靠前的越先可用。
    private TeamManager teams;
    private WallManager walls;
    private BorderManager border;
    private WitherManager withers;
    private NexusManager nexus;
    private UiManager ui;
    private StatsManager stats;
    private KitManager kits;
    private CombatManager combat;
    private LootManager loot;
    private Economy economy;
    private ArenaPool arenaPool;
    private BuildingManager buildings;
    private CustomItems items;
    private BossManager boss;
    private TeamGlowService glow;
    private MapGenerator mapGenerator;

    private String selectedArenaId;
    private WarwallArena selectedArena;

    @Override
    public void onEnable() {
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().severe("无法创建数据目录：" + getDataFolder().getAbsolutePath());
            return;
        }

        saveDefaultConfig();
        // ArenaStore.load() 本身会在文件缺失时补一份模板；这里再无条件调 saveResource
        // 只会在每次重启时打一条 "arenas.yml already exists" 的 WARN，所以先判存在性。
        if (!new java.io.File(getDataFolder(), "arenas.yml").exists()) {
            saveResource("arenas.yml", false);
        }

        config = new WarwallConfig(this);
        arenaStore = new ArenaStore(this);
        arenaStore.load();

        String configuredDefault = arenaStore.root().getString("default-arena", "");
        if (configuredDefault != null && !configuredDefault.isBlank()) {
            List<String> errors = new ArrayList<>();
            WarwallArena arena = arenaStore.read(configuredDefault, errors);
            if (arena != null) {
                selectArena(arena);
            } else {
                getLogger().warning("default-arena=" + configuredDefault + " 读取失败：" + String.join("；", errors));
            }
        }

        // 装配顺序：先建不依赖控制器的管理器，再建控制器，最后接入命令与监听器。
        teams = new TeamManager(this);
        walls = new WallManager(this);
        border = new BorderManager(this);
        withers = new WitherManager(this);
        nexus = new NexusManager(this);
        ui = new UiManager(this);
        stats = new StatsManager(this);
        kits = new KitManager(this);
        combat = new CombatManager(this);
        loot = new LootManager(this);
        economy = new Economy(this);
        arenaPool = new ArenaPool(this);
        buildings = new BuildingManager(this);
        items = new CustomItems(this);
        boss = new BossManager(this);
        glow = new TeamGlowService(this);
        mapGenerator = new MapGenerator(this);
        controller = new MatchController(this);

        // 队友发光：初始化失败就整体禁用，不半残运行。
        if (config.teamGlowEnabled() && !glow.init()) {
            getLogger().warning("队友发光不可用：" + glow.failureReason());
        }

        command = new WarwallCommand(this);
        Objects.requireNonNull(getCommand("warwall"), "plugin.yml 缺少 warwall 命令")
                .setExecutor(command);

        // 监听器
        var manager = getServer().getPluginManager();
        manager.registerEvents(new CombatListener(this), this);
        manager.registerEvents(new BlockListener(this), this);
        manager.registerEvents(new DeathListener(this), this);
        manager.registerEvents(new GameListener(this), this);

        getLogger().info("已启用：超级战墙 MegaWalls（Paper " + Bukkit.getMinecraftVersion() + "）");
        if (config.debug()) {
            getLogger().info("debug 模式已开启；当前竞技场=" + (selectedArenaId == null ? "无" : selectedArenaId));
        }
    }

    @Override
    public void onDisable() {
        if (controller != null && controller.state() != MatchState.WAITING) {
            controller.stopMatch();
        }
        if (withers != null) {
            withers.despawnAll();
        }
        if (loot != null) {
            loot.stop();
        }
        if (stats != null) {
            stats.flush();
        }
        getLogger().info("已卸载：超级战墙 MegaWalls");
    }

    // ===== 随机地图世界 =====

    /**
     * 取（或创建）随机地图专用世界。
     *
     * <p>关键点：这个世界必须用**照搬过来的 `NaturalTerrainGenerator`** 作为区块生成器，
     * 否则 `MapPreparation` 的领地评分/补偿会直接判定「主世界未启用 WarwallTool 地形生成器」而拒绝工作。
     * 用专用世界还有两个好处：不污染主世界的地形与边界，换图时整块重写即可。
     */
    public World ensureMapgenWorld() {
        String name = config.mapgenWorld();
        World existing = Bukkit.getWorld(name);
        if (existing != null) {
            // 世界已存在也要补装数据包：管理员可能手动删过
            cn.infinite.warwall.world.DatapackInstaller.ensureInstalled(this, existing);
            return existing;
        }
        try {
            World created = new org.bukkit.WorldCreator(name)
                    .generator(new cn.infinite.warwall.world.map.NaturalTerrainGenerator())
                    .environment(World.Environment.NORMAL)
                    // 必须开结构生成：MapPreparation 的领地评分会把自然遗迹计入分值，
                    // 关掉结构它就直接判定不合格。
                    .generateStructures(true)
                    .createWorld();
            if (created != null) {
                getLogger().info("已创建随机地图专用世界：" + name + "（NaturalTerrainGenerator）");
                // 世界刚建好，立刻把内置数据包放进去，否则 MapPreparation 的遗迹评分会失败
                cn.infinite.warwall.world.DatapackInstaller.ensureInstalled(this, created);
            }
            return created;
        } catch (Throwable ex) {
            getLogger().warning("创建随机地图世界失败：" + ex.getMessage() + "，回退到已有世界");
            return null;
        }
    }

    // ===== 服务访问 =====

    public TeamManager teams() {
        return teams;
    }

    public WallManager walls() {
        return walls;
    }

    public BorderManager border() {
        return border;
    }

    public WitherManager withers() {
        return withers;
    }

    public NexusManager nexus() {
        return nexus;
    }

    public UiManager ui() {
        return ui;
    }

    public StatsManager stats() {
        return stats;
    }

    public KitManager kits() {
        return kits;
    }

    public CombatManager combat() {
        return combat;
    }

    public LootManager loot() {
        return loot;
    }

    public Economy economy() {
        return economy;
    }

    public ArenaPool arenaPool() {
        return arenaPool;
    }

    public BuildingManager buildings() {
        return buildings;
    }

    public CustomItems items() {
        return items;
    }

    public BossManager boss() {
        return boss;
    }

    public TeamGlowService glow() {
        return glow;
    }

    public MapGenerator mapGenerator() {
        return mapGenerator;
    }

    public WarwallConfig config() {
        return config;
    }

    public ArenaStore arenaStore() {
        return arenaStore;
    }

    public MatchController controller() {
        return controller;
    }

    public String selectedArenaId() {
        return selectedArenaId;
    }

    public WarwallArena selectedArena() {
        return selectedArena;
    }

    public void selectArena(WarwallArena arena) {
        this.selectedArena = arena;
        this.selectedArenaId = arena == null ? null : arena.id();
    }

    /** 重载配置与竞技场；不动正在进行的一局。 */
    public void reloadAll() {
        config.reload();
        arenaStore.load();
        getLogger().info("配置已重载。");
    }

    // ===== 竞技场校验 =====

    /** 返回人类可读的问题列表；空列表表示可用。 */
    public List<String> validateArena(WarwallArena arena) {
        List<String> errors = new ArrayList<>();
        if (arena == null) {
            errors.add("尚未选择竞技场");
            return errors;
        }
        World world = Bukkit.getWorld(arena.worldName());
        if (world == null) {
            errors.add("世界 " + arena.worldName() + " 未加载");
            return errors;
        }
        if (arena.lobby() == null) {
            errors.add("缺少 lobby");
        }
        if (arena.center() == null) {
            errors.add("缺少 center");
        }
        for (TeamColor team : TeamColor.values()) {
            WarwallArena.TeamSetup setup = arena.team(team.id());
            if (setup == null) {
                errors.add("缺少队伍 " + team.id());
                continue;
            }
            if (setup.spawn() == null) {
                errors.add(team.chineseName() + " 缺少出生点");
            }
            if (setup.wither() == null) {
                errors.add(team.chineseName() + " 缺少凋零基座");
            }
        }
        long maxVolume = config.wallMaxVolume();
        for (Box box : arena.walls()) {
            if (box.volume() > maxVolume) {
                errors.add("墙区域过大：" + box.describe() + "，上限 " + maxVolume);
            }
        }
        return errors;
    }

    // ===== 调试：程序化搭建最小竞技场 =====

    /**
     * 在指定坐标原地搭一个可玩的最小竞技场，并写入 arenas.yml 的 {@code _debug}。
     *
     * <p>布局：四个 16×16 队伍平台（各自羊毛色）、平台中央 1 格凋零基座、半径 33 的 4 格高石墙、
     * 中央 5×5 石台 + 4 块钻石块、上方 20 格处大厅。用于冒烟测试，不追求美观。
     */
    public boolean debugBuildArena(CommandSender sender, String[] args) {
        if (args.length < 5) {
            sender.sendMessage(config.prefix() + "§c用法：/warwall debug arena <world> <x> <y> <z>");
            return true;
        }
        World world = Bukkit.getWorld(args[1]);
        if (world == null) {
            sender.sendMessage(config.prefix() + "§c世界不存在：" + args[1]);
            return true;
        }
        int cx;
        int cy;
        int cz;
        try {
            cx = Integer.parseInt(args[2]);
            cy = Integer.parseInt(args[3]);
            cz = Integer.parseInt(args[4]);
        } catch (NumberFormatException ex) {
            sender.sendMessage(config.prefix() + "§c坐标必须是整数。");
            return true;
        }

        // 注意：DO_MOB_SPAWNING / SEND_COMMAND_FEEDBACK 在 1.21.11 已标记待移除，这里不再下发，
        // 调试竞技场的刷怪与命令反馈由测试服自己的 server.properties 控制。

        int radius = 33;
        // 中央台
        fill(world, cx - 2, cy - 1, cz - 2, cx + 2, cy - 1, cz + 2, Material.STONE_BRICKS);
        fill(world, cx - 1, cy, cz - 1, cx + 1, cy, cz + 1, Material.DIAMOND_BLOCK);

        // 四个队伍平台
        int platform = 8;
        int[][] offsets = {{radius, 0}, {-radius, 0}, {0, radius}, {0, -radius}};
        for (int i = 0; i < TeamColor.values().length; i++) {
            TeamColor team = TeamColor.values()[i];
            int px = cx + offsets[i][0];
            int pz = cz + offsets[i][1];
            fill(world, px - platform, cy - 1, pz - platform, px + platform, cy - 1, pz + platform, team.wool());
            fill(world, px - 1, cy, pz - 1, px + 1, cy, pz + 1, Material.OBSIDIAN);
        }

        // 四面石墙（方形环，留四个门）
        for (int d = -radius; d <= radius; d++) {
            for (int h = 0; h < 4; h++) {
                place(world, cx + d, cy + h, cz - radius, Material.STONE_BRICKS);
                place(world, cx + d, cy + h, cz + radius, Material.STONE_BRICKS);
                place(world, cx - radius, cy + h, cz + d, Material.STONE_BRICKS);
                place(world, cx + radius, cy + h, cz + d, Material.STONE_BRICKS);
            }
        }

        // 写入 arenas.yml
        String id = "_debug";
        ConfigurationSection root = arenaStore.root();
        root.set("arenas." + id, null);
        ConfigurationSection section = root.createSection("arenas." + id);
        section.set("enabled", true);
        section.set("world", world.getName());
        section.set("min-players", 1);
        section.set("max-players", 100);
        writeLoc(section.createSection("center"), cx + 0.5, cy, cz + 0.5, 0f, 0f);
        writeLoc(section.createSection("lobby"), cx + 0.5, cy + 20, cz + 0.5, 0f, 0f);
        writeLoc(section.createSection("spectator"), cx + 0.5, cy + 25, cz + 0.5, 0f, 0f);
        section.set("border-center.x", cx + 0.0);
        section.set("border-center.z", cz + 0.0);

        for (int i = 0; i < TeamColor.values().length; i++) {
            TeamColor team = TeamColor.values()[i];
            int px = cx + offsets[i][0];
            int pz = cz + offsets[i][1];
            ConfigurationSection teamSection = section.createSection("teams." + team.id());
            writeLoc(teamSection.createSection("spawn"), px + 0.5, cy + 1, pz + 0.5,
                    yawTowards(offsets[i][0], offsets[i][1]), 0f);
            writeLoc(teamSection.createSection("wither"), px + 0.5, cy + 1, pz + 0.5, 0f, 0f);
            // 核心区域：以凋零基座为中心 7×5×7，调试时能真的用放方块来扣血。
            ConfigurationSection nexus = teamSection.createSection("nexus");
            nexus.set("min", java.util.Map.of("x", px - 3, "y", cy, "z", pz - 3));
            nexus.set("max", java.util.Map.of("x", px + 3, "y", cy + 4, "z", pz + 3));
        }

        List<Object> walls = new ArrayList<>();
        walls.add(ArenaStore.boxToMap(Box.of(world.getName(),
                cx - radius, cy, cz - radius, cx + radius, cy + 3, cz + radius)));
        section.set("walls", walls);
        section.set("unbreakable", new ArrayList<>());
        section.set("mining-zones", new ArrayList<>());
        section.set("no-build-zones", new ArrayList<>());
        // 中央每 60 秒掉 2 颗钻石，调试时用来验证 LootManager 的定时刷新。
        List<Object> drops = new ArrayList<>();
        drops.add(java.util.Map.of(
                "x", cx + 0.5, "y", cy + 1.0, "z", cz + 0.5,
                "every-seconds", 60, "amount", 2, "item", "DIAMOND"));
        section.set("diamond-drops", drops);
        arenaStore.save();

        List<String> errors = new ArrayList<>();
        WarwallArena built = arenaStore.read(id, errors);
        if (built == null) {
            sender.sendMessage(config.prefix() + "§c竞技场写入后读取失败：" + String.join("；", errors));
            return true;
        }
        selectArena(built);
        sender.sendMessage(config.prefix() + "§a已生成调试竞技场 _debug 并选中。可以直接 /warwall debug fast on + /warwall start。");
        return true;
    }

    private static float yawTowards(int dx, int dz) {
        // 站到平台上要面向中心，yaw 以 0=南(+z) 为基准。
        if (dx > 0) {
            return 90f;
        }
        if (dx < 0) {
            return -90f;
        }
        return dz > 0 ? 180f : 0f;
    }

    private static void writeLoc(ConfigurationSection section, double x, double y, double z, float yaw, float pitch) {
        section.set("x", x);
        section.set("y", y);
        section.set("z", z);
        section.set("yaw", (double) yaw);
        section.set("pitch", (double) pitch);
    }

    @SuppressWarnings("deprecation")
    private static void place(World world, int x, int y, int z, Material material) {
        if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
            return;
        }
        world.getBlockAt(x, y, z).setType(material, false);
    }

    private static void fill(World world, int x1, int y1, int z1, int x2, int y2, int z2, Material material) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                    place(world, x, y, z, material);
                }
            }
        }
    }
}
