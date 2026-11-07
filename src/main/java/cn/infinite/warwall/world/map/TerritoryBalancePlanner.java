package cn.infinite.warwall.world.map;

import cn.infinite.warwall.world.room.CombatTeam;
import java.util.EnumMap;
import java.util.Map;
import java.util.List;

/** Pure plans: planned points never get added to the measured score. */
public final class TerritoryBalancePlanner {
    public record Budget(int forestLogs, int ruins, Map<Resource, Integer> ores) {
        public Budget {
            if (forestLogs < 0 || ruins < 0) throw new IllegalArgumentException("补偿预算为负数");
            ores = Map.copyOf(ores);
            ores.forEach((resource, count) -> { if (resource == Resource.WOOD || count < 0) throw new IllegalArgumentException("矿物预算无效"); });
        }
        public static Budget empty() { return new Budget(0, 0, Map.of()); }
    }
    public record TeamPlan(int forestLogs, List<String> ruins, Map<Resource, Integer> ores) {
        public TeamPlan { ruins = List.copyOf(ruins); ores = Map.copyOf(ores); }
        public boolean empty() { return forestLogs == 0 && ruins.isEmpty() && ores.values().stream().allMatch(value -> value == 0); }
    }
    private final ScoreSettings settings;
    public TerritoryBalancePlanner(ScoreSettings settings) { this.settings = settings; }

    public String impossibleTerrain(ResourceMetrics partial, long expectedColumns) {
        for (CombatTeam team : CombatTeam.values()) {
            long seen = partial.columns(team);
            if (seen > expectedColumns) throw new IllegalArgumentException("扫描列数超过领地范围");
            if ((double) (partial.landColumns(team) + expectedColumns - seen) / expectedColumns + 1e-12 < settings.minimumLand())
                return team.display() + "可用陆地连最佳结果也不足最低生存要求";
        }
        return null;
    }
    public String terrainRejection(ResourceMetrics metrics) {
        for (CombatTeam team : CombatTeam.values()) if (metrics.columns(team) == 0 || metrics.landFraction(team) + 1e-12 < settings.minimumLand())
            return team.display() + "可用陆地不足最低生存要求";
        return null;
    }
    public int woodTarget(ResourceMetrics metrics, CombatTeam team) {
        long difficult = 0;
        for (TerrainKind kind : new TerrainKind[]{TerrainKind.SNOW, TerrainKind.DESERT, TerrainKind.BADLANDS, TerrainKind.MOUNTAIN})
            difficult = Math.max(difficult, metrics.terrain(team, kind));
        return difficult >= metrics.columns(team) * 0.25 || metrics.landFraction(team) < 0.55 ? settings.difficultWood() : settings.minimumWood();
    }
    public int woodTarget(ResourceMetrics metrics, CombatTeam team, TerritoryScoring.Report report) {
        if (report != null && report.teams().get(team).islandColumns() >= metrics.columns(team) * 0.25) return settings.difficultWood();
        return woodTarget(metrics, team);
    }
    public TeamPlan forest(ResourceMetrics metrics, CombatTeam team, Budget spent) {
        return forest(metrics, team, spent, null);
    }
    public TeamPlan forest(ResourceMetrics metrics, CombatTeam team, Budget spent, TerritoryScoring.Report report) {
        long missing = Math.max(0, woodTarget(metrics, team, report) - metrics.count(team, Resource.WOOD));
        if (missing > settings.maximumForestLogs() - spent.forestLogs()) throw new IllegalStateException(team.display() + "树林补偿超过限额");
        return new TeamPlan((int) missing, List.of(), Map.of());
    }
    /** Start at the median, raise only as required to retain strong natural territories without deleting resources. */
    public double target(TerritoryScoring.Report report, double previousTarget) {
        double highest = report.teams().values().stream().mapToDouble(TerritoryScoring.Breakdown::total).max().orElseThrow();
        // Invert m + max(absTolerance, abs(m) * relativeTolerance), including negative scores.
        double relativeMedian = highest / (1 + Math.copySign(settings.relativeTolerance(), highest));
        double neededMedian = Math.min(highest - settings.absoluteTolerance(), relativeMedian);
        // Aim slightly inside the acceptance band rather than asymptotically approaching its upper edge.
        neededMedian += settings.absoluteTolerance() * 0.25;
        return Math.max(Math.max(report.median(), neededMedian), previousTarget);
    }
    public boolean balanced(ResourceMetrics metrics, TerritoryScoring.Report report) {
        if (terrainRejection(metrics) != null || !ArenaGeometry.nearResourcesReady(metrics)) return false;
        double tolerance = settings.tolerance(report.median());
        for (CombatTeam team : CombatTeam.values()) {
            if (Math.abs(report.teams().get(team).total() - report.median()) > tolerance + 1e-8
                    || metrics.count(team, Resource.WOOD) < woodTarget(metrics, team, report)) return false;
            for (Resource resource : Resource.values()) if (metrics.count(team, resource) < resource.minimum()) return false;
        }
        return true;
    }
    public TeamPlan compensate(ResourceMetrics metrics, CombatTeam team, double currentScore, double target, Budget spent) {
        EnumMap<Resource, Integer> ores = new EnumMap<>(Resource.class);
        double guaranteedMinimumGain = 0;
        for (Resource resource : Resource.values()) {
            if (resource == Resource.WOOD) continue;
            int minimum = (int) Math.max(0, resource.minimum() - metrics.count(team, resource));
            int remaining = settings.maximumOrePerResource() - spent.ores().getOrDefault(resource, 0);
            if (minimum > remaining) throw new IllegalStateException(team.display() + resource.display() + "生存补齐超过限额");
            ores.put(resource, minimum); guaranteedMinimumGain += minimum * settings.resourceWeights().get(resource);
        }
        double deficit = Math.min(settings.maximumRoundPoints(), Math.max(0, target - currentScore - guaranteedMinimumGain));
        List<String> ruins = OriginalRuinCatalog.plan(deficit * 0.5, settings.maximumRuins() - spent.ruins(), settings::structurePoints);
        double remainingPoints = deficit - ruins.stream().mapToDouble(settings::structurePoints).sum();
        Resource[] order = {Resource.IRON, Resource.COAL, Resource.GOLD, Resource.DIAMOND};
        double[] shares = {0.40, 0.20, 0.25, 0.15};
        double unallocated = remainingPoints;
        for (int i = 0; i < order.length; i++) {
            Resource resource = order[i];
            // A new ore might be exposed; budget using its maximum possible point gain to limit overshoot.
            double upperGain = settings.resourceWeights().get(resource) * (1 + settings.exposedMultiplier());
            int capacity = settings.maximumOrePerResource() - spent.ores().getOrDefault(resource, 0) - ores.get(resource);
            int count = affordable(Math.min(unallocated, remainingPoints * shares[i]), upperGain, capacity);
            ores.merge(resource, count, Integer::sum);
            unallocated = Math.max(0, unallocated - count * upperGain);
        }
        // Redistribute unused shares when one ore hits its cap. Never round an unaffordable block up.
        for (Resource resource : order) {
            double upperGain = settings.resourceWeights().get(resource) * (1 + settings.exposedMultiplier());
            int capacity = settings.maximumOrePerResource() - spent.ores().getOrDefault(resource, 0) - ores.get(resource);
            int count = affordable(unallocated, upperGain, capacity);
            ores.merge(resource, count, Integer::sum);
            unallocated = Math.max(0, unallocated - count * upperGain);
        }
        // An exhausted team must not abort useful work on other teams. The caller rejects if ALL plans are empty.
        return new TeamPlan(0, ruins, Map.copyOf(ores));
    }
    private static int affordable(double points, double unitCost, int capacity) {
        return Math.max(0, Math.min(capacity, (int) Math.floor((points + 1e-9) / unitCost)));
    }
}

