package cn.infinite.warwall.world.map;

import cn.infinite.warwall.world.room.CombatTeam;
import java.util.EnumMap;
import java.util.Map;

public final class TerritoryScoring {
    public record Breakdown(double resources, double exposed, double structures, double land,
                            double contiguousLand, double penalty, double total, long largestArea, long islandColumns) { }
    public record Report(Map<CombatTeam, Breakdown> teams, double median) { }
    private TerritoryScoring() { }
    public static Report evaluate(ResourceMetrics metrics, ScoreSettings settings) {
        LandTopology.Result topology = LandTopology.analyze(metrics, settings.islandMaxArea());
        EnumMap<CombatTeam, Breakdown> teams = new EnumMap<>(CombatTeam.class);
        for (CombatTeam team : CombatTeam.values()) {
            double resources = 0, exposed = 0, structures = 0, penalty = 0;
            for (Resource resource : Resource.values()) {
                resources += metrics.count(team, resource) * settings.resourceWeights().get(resource);
                if (resource != Resource.WOOD) exposed += metrics.exposed(team, resource)
                        * settings.resourceWeights().get(resource) * settings.exposedMultiplier();
            }
            for (TerritoryStructure structure : metrics.structures()) if (structure.team() == team) structures += settings.structurePoints(structure.kind()) * structure.share();
            long island = topology.islandColumns().get(team), largest = topology.largestGentleArea().get(team);
            for (TerrainKind kind : TerrainKind.values()) penalty += (kind == TerrainKind.ISLAND ? island : metrics.terrain(team, kind))
                    / 1000.0 * settings.penalties().get(kind);
            double land = metrics.landColumns(team) / 1000.0 * settings.landPer1000();
            double contiguous = largest / 1000.0 * settings.contiguousPer1000();
            teams.put(team, new Breakdown(resources, exposed, structures, land, contiguous, penalty,
                    settings.baseScore() + resources + exposed + structures + land + contiguous - penalty, largest, island));
        }
        double[] scores = teams.values().stream().mapToDouble(Breakdown::total).sorted().toArray();
        return new Report(Map.copyOf(teams), (scores[1] + scores[2]) / 2);
    }
}

