package cn.infinite.warwall.world.map;

import cn.infinite.warwall.world.room.CombatTeam;
import java.util.ArrayList;
import java.util.List;

/** Stable identity de-duplicates one generated structure reported by several chunks. */
public record TerritoryStructure(String id, String kind, CombatTeam team, int minX, int minZ, int maxX, int maxZ, double share, int minY, int maxY) {
    public TerritoryStructure(String id, String kind, CombatTeam team, int minX, int minZ, int maxX, int maxZ, double share) {
        this(id, kind, team, minX, minZ, maxX, maxZ, share, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }
    public TerritoryStructure {
        if (id == null || kind == null || team == null || minX > maxX || minZ > maxZ || minY > maxY || !Double.isFinite(share) || share <= 0 || share > 1) throw new IllegalArgumentException("遗迹统计无效");
    }
    public static List<TerritoryStructure> natural(String kind, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        if (minX > maxX || minZ > maxZ || minY > maxY) throw new IllegalArgumentException("遗迹边界无效");
        String id = kind + ":" + minX + ":" + minY + ":" + minZ + ":" + maxX + ":" + maxY + ":" + maxZ;
        double area = ((long) maxX - minX + 1) * (double) ((long) maxZ - minZ + 1);
        List<TerritoryStructure> result = new ArrayList<>();
        for (CombatTeam team : CombatTeam.values()) {
            int x1 = Math.max(minX, team.signX() > 0 ? 3 : -ArenaGeometry.RADIUS);
            int x2 = Math.min(maxX, team.signX() > 0 ? ArenaGeometry.RADIUS : -3);
            int z1 = Math.max(minZ, team.signZ() > 0 ? 3 : -ArenaGeometry.RADIUS);
            int z2 = Math.min(maxZ, team.signZ() > 0 ? ArenaGeometry.RADIUS : -3);
            if (x1 > x2 || z1 > z2) continue;
            long owned = (long) (x2 - x1 + 1) * (z2 - z1 + 1);
            int cx1 = Math.max(x1, -ArenaGeometry.CENTRAL_RADIUS), cx2 = Math.min(x2, ArenaGeometry.CENTRAL_RADIUS);
            int cz1 = Math.max(z1, -ArenaGeometry.CENTRAL_RADIUS), cz2 = Math.min(z2, ArenaGeometry.CENTRAL_RADIUS);
            if (cx1 <= cx2 && cz1 <= cz2) owned -= (long) (cx2 - cx1 + 1) * (cz2 - cz1 + 1);
            if (owned > 0) result.add(new TerritoryStructure(id + ":" + team.key(), kind, team, minX, minZ, maxX, maxZ, owned / area, minY, maxY));
        }
        return List.copyOf(result);
    }
    public boolean overlaps(int x1, int z1, int x2, int z2, int margin) {
        return x1 <= maxX + margin && x2 >= minX - margin && z1 <= maxZ + margin && z2 >= minZ - margin;
    }
    public boolean overlaps(int x1, int z1, int x2, int z2, int y1, int y2, int margin) {
        return (long) y1 <= (long) maxY + margin && (long) y2 >= (long) minY - margin && overlaps(x1, z1, x2, z2, margin);
    }
}

