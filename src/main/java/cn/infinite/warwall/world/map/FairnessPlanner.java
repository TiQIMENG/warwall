package cn.infinite.warwall.world.map;

import cn.infinite.warwall.world.room.CombatTeam;
import java.util.EnumMap;
import java.util.Map;

public final class FairnessPlanner {
    public record Plan(boolean accepted, String reason, Map<CombatTeam, Map<Resource, Integer>> supplements) { }
    private final double resourceRatio, landSpread;
    private final int maxSupplement;
    public FairnessPlanner(double resourceRatio, double landSpread, int maxSupplement) {
        if (!Double.isFinite(resourceRatio) || resourceRatio < 1 || resourceRatio > 3
                || !Double.isFinite(landSpread) || landSpread < 0 || landSpread > 0.5 || maxSupplement < 0 || maxSupplement > 16384) {
            throw new IllegalArgumentException("地图公平性阈值无效。");
        }
        this.resourceRatio = resourceRatio; this.landSpread = landSpread; this.maxSupplement = maxSupplement;
    }
    public Plan evaluate(ResourceMetrics metrics) {
        String terrain = terrainRejection(metrics);
        if (terrain != null) return rejected(terrain);
        EnumMap<CombatTeam, Map<Resource, Integer>> supplements = new EnumMap<>(CombatTeam.class);
        for (CombatTeam team : CombatTeam.values()) supplements.put(team, new EnumMap<>(Resource.class));
        for (Resource resource : Resource.values()) {
            long largest = 0;
            for (CombatTeam team : CombatTeam.values()) largest = Math.max(largest, metrics.count(team, resource));
            long target = Math.max(resource.minimum(), (long) Math.ceil(largest / resourceRatio));
            for (CombatTeam team : CombatTeam.values()) {
                long missing = Math.max(0, target - metrics.count(team, resource));
                if (missing > maxSupplement) return rejected(team.display() + resource.display() + "差距过大：需补 " + missing + "，上限 " + maxSupplement);
                supplements.get(team).put(resource, (int) missing);
            }
        }
        EnumMap<CombatTeam, Map<Resource, Integer>> frozen = new EnumMap<>(CombatTeam.class);
        supplements.forEach((team, values) -> frozen.put(team, Map.copyOf(values)));
        return new Plan(true, "资源差距可在补齐上限内修正", Map.copyOf(frozen));
    }
    public String terrainRejection(ResourceMetrics metrics) {
        double minLand = 1, maxLand = 0;
        for (CombatTeam team : CombatTeam.values()) {
            if (metrics.columns(team) == 0) return "缺少象限扫描结果";
            double land = metrics.landFraction(team);
            if (land < 0.55) return team.display() + "陆地不足：" + Math.round(land * 100) + "%";
            minLand = Math.min(minLand, land); maxLand = Math.max(maxLand, land);
        }
        return maxLand - minLand > landSpread + 1e-12 ? "四队陆地比例差距过大" : null;
    }
    /** Reject only if even the best possible remaining columns cannot pass. No sample-based guesses. */
    public String impossibleTerrain(ResourceMetrics partial, long expectedColumnsPerTeam) {
        if (expectedColumnsPerTeam <= 0) throw new IllegalArgumentException("象限列数必须为正数");
        double highestLower = 0, lowestUpper = 1;
        for (CombatTeam team : CombatTeam.values()) {
            long seen = partial.columns(team), land = partial.landColumns(team);
            if (seen > expectedColumnsPerTeam) throw new IllegalArgumentException("扫描列数超过完整象限");
            double lower = (double) land / expectedColumnsPerTeam;
            double upper = (double) (land + expectedColumnsPerTeam - seen) / expectedColumnsPerTeam;
            if (upper + 1e-12 < 0.55) return team.display() + "即使剩余全是陆地也不足 55%";
            highestLower = Math.max(highestLower, lower);
            lowestUpper = Math.min(lowestUpper, upper);
        }
        return highestLower - lowestUpper > landSpread + 1e-12 ? "剩余地形已无法修正四队陆地差距" : null;
    }
    private static Plan rejected(String reason) { return new Plan(false, reason, Map.of()); }
}

