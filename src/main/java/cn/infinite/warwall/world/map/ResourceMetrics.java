package cn.infinite.warwall.world.map;

import java.util.EnumMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import cn.infinite.warwall.world.room.CombatTeam;

/** 只保存计数，区块快照由后台扫描后释放，不长期缓存整张地图。 */
public final class ResourceMetrics {
    private final EnumMap<CombatTeam, long[]> resources = new EnumMap<>(CombatTeam.class);
    private final EnumMap<CombatTeam, long[]> surface = new EnumMap<>(CombatTeam.class);
    private final EnumMap<CombatTeam, long[]> exposed = new EnumMap<>(CombatTeam.class);
    private final EnumMap<CombatTeam, long[]> terrain = new EnumMap<>(CombatTeam.class);
    private final List<SurfaceCell> cells = new ArrayList<>();
    private final Map<String, TerritoryStructure> structures = new LinkedHashMap<>();
    public record SurfaceCell(int x, int z, int groundY, boolean land, boolean water) { }
    public ResourceMetrics() { for (CombatTeam team : CombatTeam.values()) {
        resources.put(team, new long[Resource.values().length]); surface.put(team, new long[4]);
        exposed.put(team, new long[Resource.values().length]); terrain.put(team, new long[TerrainKind.values().length]);
    } }
    public void resource(CombatTeam team, Resource resource, long count) { resources.get(team)[resource.ordinal()] += count; }
    public long count(CombatTeam team, Resource resource) { return resources.get(team)[resource.ordinal()]; }
    public void exposed(CombatTeam team, Resource resource, long count) { exposed.get(team)[resource.ordinal()] += count; }
    public long exposed(CombatTeam team, Resource resource) { return exposed.get(team)[resource.ordinal()]; }
    public void terrain(CombatTeam team, TerrainKind kind, long count) { terrain.get(team)[kind.ordinal()] += count; }
    public long terrain(CombatTeam team, TerrainKind kind) { return terrain.get(team)[kind.ordinal()]; }
    public void cell(int x, int z, int groundY, boolean land, boolean water) { cells.add(new SurfaceCell(x, z, groundY, land, water)); }
    public List<SurfaceCell> cells() { return List.copyOf(cells); }
    public void structure(TerritoryStructure structure) {
        TerritoryStructure old = structures.putIfAbsent(structure.id(), structure);
        if (old != null && !old.equals(structure)) throw new IllegalArgumentException("遗迹标识冲突");
    }
    public Collection<TerritoryStructure> structures() { return List.copyOf(structures.values()); }
    public long structures(CombatTeam team) { return structures.values().stream().filter(value -> value.team() == team).count(); }
    public void surface(CombatTeam team, boolean land, boolean nearWater, boolean nearWood) {
        long[] values = surface.get(team); values[0]++; if (land) values[1]++; if (nearWater) values[2]++; if (nearWood) values[3]++;
    }
    public long columns(CombatTeam team) { return surface.get(team)[0]; }
    public long landColumns(CombatTeam team) { return surface.get(team)[1]; }
    public double landFraction(CombatTeam team) { return columns(team) == 0 ? 0 : (double) surface.get(team)[1] / columns(team); }
    public long nearbyWater(CombatTeam team) { return surface.get(team)[2]; }
    public long nearbyWood(CombatTeam team) { return surface.get(team)[3]; }
    public void merge(ResourceMetrics other) {
        for (CombatTeam team : CombatTeam.values()) {
            for (Resource resource : Resource.values()) {
                resource(team, resource, other.count(team, resource)); exposed(team, resource, other.exposed(team, resource));
            }
            for (TerrainKind kind : TerrainKind.values()) terrain(team, kind, other.terrain(team, kind));
            for (int i = 0; i < 4; i++) surface.get(team)[i] += other.surface.get(team)[i];
        }
        cells.addAll(other.cells);
        other.structures.values().forEach(this::structure);
    }
    public static CombatTeam quadrant(int x, int z) {
        if (x == 0 || z == 0) throw new IllegalArgumentException("中心轴不属于任一队伍。");
        if (x > 0) return z > 0 ? CombatTeam.RED : CombatTeam.BLUE;
        return z > 0 ? CombatTeam.YELLOW : CombatTeam.GREEN;
    }
}

