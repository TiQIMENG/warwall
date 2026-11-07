package cn.infinite.warwall.arena;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * arenas.yml 的读写。
 *
 * <p>文件只在 {@link #load()} 与 {@link #save()} 时碰磁盘，运行期全部走内存里的
 * {@link YamlConfiguration}，避免在事件回调里做 IO。
 */
public final class ArenaStore {

    private final JavaPlugin plugin;
    private final File file;
    private YamlConfiguration root;

    public ArenaStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "arenas.yml");
    }

    /** 从磁盘读入；文件不存在时生成模板。 */
    public void load() {
        if (!file.exists()) {
            plugin.saveResource("arenas.yml", false);
        }
        root = YamlConfiguration.loadConfiguration(file);
    }

    /** 写回磁盘；IO 失败只记录日志，不抛给调用方。 */
    public boolean save() {
        if (root == null) {
            return false;
        }
        try {
            root.save(file);
            return true;
        } catch (IOException ex) {
            plugin.getLogger().severe("保存 arenas.yml 失败：" + ex.getMessage());
            return false;
        }
    }

    public YamlConfiguration root() {
        if (root == null) {
            root = new YamlConfiguration();
        }
        return root;
    }

    /** 已配置的竞技场 id 列表。 */
    public List<String> arenaIds() {
        ConfigurationSection section = root().getConfigurationSection("arenas");
        return section == null ? List.of() : new ArrayList<>(section.getKeys(false));
    }

    /** 读出一个竞技场；不存在或字段非法时返回 null 并填充 errors。 */
    public WarwallArena read(String id, List<String> errors) {
        ConfigurationSection section = root().getConfigurationSection("arenas." + id);
        if (section == null) {
            errors.add("竞技场 " + id + " 不存在");
            return null;
        }
        return readArena(id, section, errors);
    }

    /**
     * 从配置节点构造竞技场。缺字段统一记进 errors，由命令层一次性回显，
     * 避免玩家一条一条试错。
     */
    static WarwallArena readArena(String id, ConfigurationSection section, List<String> errors) {
        String world = section.getString("world", "");
        if (world.isBlank()) {
            errors.add("缺少 world");
        }
        int minPlayers = section.getInt("min-players", 8);
        int maxPlayers = section.getInt("max-players", 100);

        WarwallArena.SpawnPoint lobby = readSpawn(section.getConfigurationSection("lobby"), errors, "lobby");
        WarwallArena.SpawnPoint spectator = readSpawn(section.getConfigurationSection("spectator"), errors, "spectator");
        WarwallArena.SpawnPoint center = readSpawn(section.getConfigurationSection("center"), errors, "center");

        Map<String, WarwallArena.TeamSetup> teams = new LinkedHashMap<>();
        for (String teamId : new String[] {"red", "yellow", "green", "blue"}) {
            ConfigurationSection teamSection = section.getConfigurationSection("teams." + teamId);
            if (teamSection == null) {
                errors.add("缺少队伍 " + teamId);
                continue;
            }
            WarwallArena.SpawnPoint spawn =
                    readSpawn(teamSection.getConfigurationSection("spawn"), errors, teamId + ".spawn");
            WarwallArena.SpawnPoint respawn =
                    readSpawn(teamSection.getConfigurationSection("respawn"), errors, teamId + ".respawn");
            WarwallArena.SpawnPoint wither =
                    readSpawn(teamSection.getConfigurationSection("wither"), errors, teamId + ".wither");
            Box nexus = readSingleBox(teamSection.getConfigurationSection("nexus"), world);
            teams.put(teamId, new WarwallArena.TeamSetup(spawn, respawn == null ? spawn : respawn, wither, nexus));
        }

        List<Box> walls = readBoxes(section.getMapList("walls"), world, errors, "walls");
        List<Box> unbreakable = readBoxes(section.getMapList("unbreakable"), world, errors, "unbreakable");
        List<Box> mining = readBoxes(section.getMapList("mining-zones"), world, errors, "mining-zones");
        List<Box> noBuild = readBoxes(section.getMapList("no-build-zones"), world, errors, "no-build-zones");

        List<WarwallArena.DiamondDrop> drops = new ArrayList<>();
        for (Map<?, ?> raw : section.getMapList("diamond-drops")) {
            Object itemRaw = raw.get("item");
            String itemName = itemRaw instanceof String text ? text : "DIAMOND";
            drops.add(new WarwallArena.DiamondDrop(
                    asDouble(raw.get("x"), 0.5),
                    asDouble(raw.get("y"), 65.0),
                    asDouble(raw.get("z"), 0.5),
                    asInt(raw.get("every-seconds"), 300),
                    asInt(raw.get("amount"), 2),
                    itemName));
        }

        double borderX = section.getDouble("border-center.x", center == null ? 0.0 : center.x());
        double borderZ = section.getDouble("border-center.z", center == null ? 0.0 : center.z());

        return new WarwallArena(id, section.getBoolean("enabled", true), world, minPlayers, maxPlayers,
                lobby, spectator, center, borderX, borderZ, teams, walls, unbreakable, mining, noBuild, drops);
    }

    private static WarwallArena.SpawnPoint readSpawn(ConfigurationSection section, List<String> errors, String path) {
        if (section == null) {
            return null;
        }
        if (!section.isSet("x") || !section.isSet("y") || !section.isSet("z")) {
            errors.add(path + " 缺少 x/y/z");
            return null;
        }
        return new WarwallArena.SpawnPoint(
                section.getDouble("x"),
                section.getDouble("y"),
                section.getDouble("z"),
                (float) section.getDouble("yaw", 0.0),
                (float) section.getDouble("pitch", 0.0));
    }

    /** 读一个 {@code {min:{x,y,z}, max:{x,y,z}}} 节点；节点缺失返回 null（核心是可选配置）。 */
    private static Box readSingleBox(ConfigurationSection section, String world) {
        if (section == null) {
            return null;
        }
        ConfigurationSection min = section.getConfigurationSection("min");
        ConfigurationSection max = section.getConfigurationSection("max");
        if (min == null || max == null) {
            return null;
        }
        return Box.of(world,
                min.getInt("x"), min.getInt("y"), min.getInt("z"),
                max.getInt("x"), max.getInt("y"), max.getInt("z"));
    }

    private static List<Box> readBoxes(List<Map<?, ?>> raw, String world, List<String> errors, String path) {        List<Box> boxes = new ArrayList<>();
        for (Map<?, ?> entry : raw) {
            Object minRaw = entry.get("min");
            Object maxRaw = entry.get("max");
            if (!(minRaw instanceof Map<?, ?> min) || !(maxRaw instanceof Map<?, ?> max)) {
                errors.add(path + " 里有一项缺少 min/max");
                continue;
            }
            boxes.add(Box.of(world,
                    asInt(min.get("x"), 0), asInt(min.get("y"), 0), asInt(min.get("z"), 0),
                    asInt(max.get("x"), 0), asInt(max.get("y"), 0), asInt(max.get("z"), 0)));
        }
        return boxes;
    }

    private static int asInt(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static double asDouble(Object value, double fallback) {
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    /** 把玩家当前坐标写成一个生效中的出生点节点。 */
    public static void writeSpawn(ConfigurationSection section, Location location) {
        if (section == null || location == null) {
            return;
        }
        section.set("x", round(location.getX()));
        section.set("y", round(location.getY()));
        section.set("z", round(location.getZ()));
        section.set("yaw", round(location.getYaw()));
        section.set("pitch", round(location.getPitch()));
    }

    /** 把一个盒子写成 {@code {min:{x,y,z}, max:{x,y,z}}} 节点列表项。 */
    public static Map<String, Object> boxToMap(Box box) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("min", Map.of("x", box.minX(), "y", box.minY(), "z", box.minZ()));
        map.put("max", Map.of("x", box.maxX(), "y", box.maxY(), "z", box.maxZ()));
        return map;
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    /** 世界名 → 已加载世界；未加载返回 null（调用方负责报错）。 */
    public static World worldOrNull(String name) {
        return name == null ? null : Bukkit.getWorld(name);
    }

    /** 队伍 id 小写化，避免配置大小写不一致导致取不到。 */
    public static String normalizeTeamId(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }
}
