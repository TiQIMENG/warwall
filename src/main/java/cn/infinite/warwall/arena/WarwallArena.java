package cn.infinite.warwall.arena;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

/**
 * 一个竞技场的静态配置快照。由 {@link ArenaStore} 从 arenas.yml 读出，运行时只读。
 *
 * <p>坐标全部是「方块坐标 + 浮点偏移」的写法，与 arenas.yml 一一对应：
 * {@code lobby: {x,y,z,yaw,pitch}}。
 */
public final class WarwallArena {

    /** 一个带朝向的出生点。 */
    public record SpawnPoint(double x, double y, double z, float yaw, float pitch) {

        /** 转成真正的 {@link Location}；世界未加载时返回 null。 */
        public Location toLocation(String worldName) {
            World world = Bukkit.getWorld(worldName);
            return world == null ? null : new Location(world, x, y, z, yaw, pitch);
        }
    }

    /**
     * 一个队伍的配置。
     *
     * @param nexus 核心（Nexus）区域；为 null 表示该队本局没有核心，核心机制对该队失效
     */
    public record TeamSetup(SpawnPoint spawn, SpawnPoint respawn, SpawnPoint wither, Box nexus) {
    }

    /** 定时刷新的中央掉落物。 */
    public record DiamondDrop(double x, double y, double z, int everySeconds, int amount, String item) {
    }

    private final String id;
    private final boolean enabled;
    private final String worldName;
    private final int minPlayers;
    private final int maxPlayers;
    private final SpawnPoint lobby;
    private final SpawnPoint spectator;
    private final SpawnPoint center;
    private final double borderCenterX;
    private final double borderCenterZ;
    private final Map<String, TeamSetup> teams;
    private final List<Box> walls;
    private final List<Box> unbreakable;
    private final List<Box> miningZones;
    private final List<Box> noBuildZones;
    private final List<DiamondDrop> diamondDrops;

    public WarwallArena(String id, boolean enabled, String worldName, int minPlayers, int maxPlayers,
                        SpawnPoint lobby, SpawnPoint spectator, SpawnPoint center,
                        double borderCenterX, double borderCenterZ,
                        Map<String, TeamSetup> teams,
                        List<Box> walls, List<Box> unbreakable,
                        List<Box> miningZones, List<Box> noBuildZones,
                        List<DiamondDrop> diamondDrops) {
        this.id = id;
        this.enabled = enabled;
        this.worldName = worldName;
        this.minPlayers = minPlayers;
        this.maxPlayers = maxPlayers;
        this.lobby = lobby;
        this.spectator = spectator;
        this.center = center;
        this.borderCenterX = borderCenterX;
        this.borderCenterZ = borderCenterZ;
        this.teams = Map.copyOf(teams);
        this.walls = List.copyOf(walls);
        this.unbreakable = List.copyOf(unbreakable);
        this.miningZones = List.copyOf(miningZones);
        this.noBuildZones = List.copyOf(noBuildZones);
        this.diamondDrops = List.copyOf(diamondDrops);
    }

    public String id() {
        return id;
    }

    public boolean enabled() {
        return enabled;
    }

    public String worldName() {
        return worldName;
    }

    public int minPlayers() {
        return minPlayers;
    }

    public int maxPlayers() {
        return maxPlayers;
    }

    public SpawnPoint lobby() {
        return lobby;
    }

    public SpawnPoint spectator() {
        return spectator;
    }

    public SpawnPoint center() {
        return center;
    }

    public double borderCenterX() {
        return borderCenterX;
    }

    public double borderCenterZ() {
        return borderCenterZ;
    }

    public TeamSetup team(String teamId) {
        return teams.get(teamId.toLowerCase(Locale.ROOT));
    }

    public Map<String, TeamSetup> teams() {
        return teams;
    }

    public List<Box> walls() {
        return walls;
    }

    public List<Box> unbreakable() {
        return unbreakable;
    }

    public List<Box> miningZones() {
        return miningZones;
    }

    public List<Box> noBuildZones() {
        return noBuildZones;
    }

    public List<DiamondDrop> diamondDrops() {
        return diamondDrops;
    }

    /** 世界是否已经加载。 */
    public boolean worldLoaded() {
        return worldName != null && Bukkit.getWorld(worldName) != null;
    }

    /** 坐标是否落在任何「不可破坏」盒子里。 */
    public boolean isUnbreakable(String world, int x, int y, int z) {
        for (Box box : unbreakable) {
            if (box.world().equalsIgnoreCase(world) && box.contains(x, y, z)) {
                return true;
            }
        }
        return false;
    }

    /** 该坐标是否允许被破坏：mining-zones 为空表示不限制。 */
    public boolean isMineable(String world, int x, int y, int z) {
        if (miningZones.isEmpty()) {
            return true;
        }
        for (Box box : miningZones) {
            if (box.world().equalsIgnoreCase(world) && box.contains(x, y, z)) {
                return true;
            }
        }
        return false;
    }

    /** 该坐标是否禁止放置方块。 */
    public boolean isNoBuild(String world, int x, int y, int z) {
        for (Box box : noBuildZones) {
            if (box.world().equalsIgnoreCase(world) && box.contains(x, y, z)) {
                return true;
            }
        }
        return false;
    }

    /** 从 arenas.yml 的一个子节点读出一个竞技场；缺字段会写进返回的错误列表。 */
    public static WarwallArena read(String id, ConfigurationSection section, List<String> errors) {
        // 具体实现见 ArenaStore；这里仅暴露契约，保持模型层薄。
        return ArenaStore.readArena(id, section, errors);
    }
}
