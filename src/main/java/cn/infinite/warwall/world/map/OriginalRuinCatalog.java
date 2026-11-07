package cn.infinite.warwall.world.map;

import cn.infinite.warwall.world.room.CombatTeam;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/** Metadata only, audited against wallwar 6dafba2; no third-party NBT is bundled. */
public final class OriginalRuinCatalog {
    public static final String SOURCE_COMMIT = "6dafba2c738be32dd748d708275fcfe97c1ea33e";
    public record Spec(String key, int sizeX, int sizeY, int sizeZ, boolean surface, boolean compensate, String lootTable) { }
    private static Spec spec(String name, int x, int y, int z, boolean surface, boolean compensate, String loot) {
        return new Spec("wallwar:" + name, x, y, z, surface, compensate, loot == null ? null : "wallwar:structure/" + loot);
    }
    private static final List<Spec> ALL = List.of(
            spec("brew", 35, 8, 35, false, false, "brew"),
            spec("electric", 3, 8, 3, true, true, null),
            spec("farmland", 7, 2, 7, true, true, null),
            spec("fishhouse", 13, 12, 18, true, false, "fishhouse"),
            spec("flag", 3, 4, 3, true, true, null),
            spec("gift", 33, 6, 33, false, false, "gift"),
            spec("prison", 38, 6, 36, false, false, "prison"),
            spec("respawn", 5, 4, 5, true, true, null),
            spec("spawn", 5, 6, 5, true, true, null),
            spec("tower", 5, 11, 5, true, true, null),
            spec("trans", 3, 5, 3, true, true, "trans"),
            spec("wood", 9, 7, 9, false, false, null));
    public static final Map<String, Double> DEFAULT_POINTS = Map.ofEntries(
            Map.entry("wallwar:brew", 180.0), Map.entry("wallwar:electric", 35.0),
            Map.entry("wallwar:farmland", 60.0), Map.entry("wallwar:fishhouse", 120.0),
            Map.entry("wallwar:flag", 20.0), Map.entry("wallwar:gift", 90.0),
            Map.entry("wallwar:prison", 160.0), Map.entry("wallwar:respawn", 55.0),
            Map.entry("wallwar:spawn", 50.0), Map.entry("wallwar:tower", 65.0),
            Map.entry("wallwar:trans", 80.0), Map.entry("wallwar:wood", 45.0),
            Map.entry("wallwar:tutoriel", 0.0));
    public static final Map<String, String> MATERIAL_FINGERPRINTS = Map.of(
            "wallwar:electric", "6190ece04bcb102f40491b90797d979a3fab4cadd7974c0a29bdf909471a95af",
            "wallwar:farmland", "28b40f523984125c1ec70887602e1d747287ae248d58fbf86a72605e4d1b05fc",
            "wallwar:flag", "2aa7ab420e5805eadc75b72f238e23c589e1af85f8933028432872b9ea16df10",
            "wallwar:respawn", "450f1953f9a9962662203779cbceeda3bf7928b2ccf2d7cf4961b682de897e50",
            "wallwar:spawn", "7b998b3661861f3ae9d0774e4b561e14a6f53f270c21b349a345e3f831316fc1",
            "wallwar:tower", "052733506a3a43fe57429c5d5933c1b93b27b43b684978523211525b5f1f0682",
            "wallwar:trans", "528d13a9f504b6d6f03819043109172b20a121c94b537d6e81611c170bfefb1c");
    private OriginalRuinCatalog() { }
    public static List<Spec> all() { return ALL; }
    public static List<Spec> compensation() { return ALL.stream().filter(Spec::compensate).toList(); }
    public static Spec get(String key) {
        return ALL.stream().filter(spec -> spec.key().equals(key)).findFirst().orElseThrow(() -> new IllegalArgumentException("未登记的原创遗迹：" + key));
    }
    /** Prefer a high-value fitting template, then different kinds; count and points are bounded. */
    public static List<String> plan(double availablePoints, int capacity, ToDoubleFunction<String> points) {
        if (!Double.isFinite(availablePoints) || availablePoints < 0 || capacity < 0) throw new IllegalArgumentException("原创遗迹预算无效");
        List<Spec> candidates = compensation().stream().filter(spec -> points.applyAsDouble(spec.key()) > 0)
                .sorted(Comparator.<Spec>comparingDouble(spec -> points.applyAsDouble(spec.key())).reversed().thenComparing(Spec::key)).toList();
        List<String> chosen = new ArrayList<>();
        double remaining = availablePoints;
        while (chosen.size() < capacity) {
            Spec next = null;
            for (Spec candidate : candidates) if (points.applyAsDouble(candidate.key()) <= remaining + 1e-8
                    && !chosen.contains(candidate.key())) { next = candidate; break; }
            if (next == null) break;
            chosen.add(next.key()); remaining -= points.applyAsDouble(next.key());
        }
        return List.copyOf(chosen);
    }
    public static boolean fits(Spec spec, CombatTeam team, int x, int z) {
        int endX = x + spec.sizeX() - 1, endZ = z + spec.sizeZ() - 1;
        if (!ArenaGeometry.counted(x, z, ArenaGeometry.RADIUS) || !ArenaGeometry.counted(endX, endZ, ArenaGeometry.RADIUS)
                || ResourceMetrics.quadrant(x, z) != team || ResourceMetrics.quadrant(endX, endZ) != team) return false;
        int central = ArenaGeometry.CENTRAL_RADIUS + 3;
        return !(x <= central && endX >= -central && z <= central && endZ >= -central);
    }
}

