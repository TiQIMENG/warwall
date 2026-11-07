package cn.infinite.warwall.world.map;

import java.util.EnumMap;
import java.util.Map;
import java.util.TreeMap;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** Validated immutable settings; the signature includes every scoring and compensation limit. */
public record ScoreSettings(double baseScore, double landPer1000, double contiguousPer1000,
                            double exposedMultiplier, double relativeTolerance, double absoluteTolerance,
                            double minimumLand, int minimumWood, int difficultWood, int maximumForestLogs,
                            int maximumRuins, int maximumOrePerResource, int maximumRounds,
                            double maximumRoundPoints, double ruinPoints, int islandMaxArea,
                            Map<Resource, Double> resourceWeights, Map<TerrainKind, Double> penalties,
                            Map<String, Double> structureWeights) {
    public ScoreSettings {
        check(baseScore, 0, 100000, "base"); check(landPer1000, 0, 100, "land");
        check(contiguousPer1000, 0, 100, "contiguous-land"); check(exposedMultiplier, 0, 10, "exposed");
        check(relativeTolerance, 0, 0.25, "relative-tolerance"); check(absoluteTolerance, 0.01, 100, "absolute-tolerance");
        check(minimumLand, 0.05, 0.8, "minimum-land"); check(maximumRoundPoints, 1, 5000, "round-points");
        check(ruinPoints, 1, 1000, "ruin-points");
        if (minimumWood < Resource.WOOD.minimum() || difficultWood < minimumWood || difficultWood > 8192
                || maximumForestLogs < 4 || maximumForestLogs > 16384 || maximumRuins < 0 || maximumRuins > 32
                || maximumOrePerResource < 128 || maximumOrePerResource > 16384 || maximumRounds < 1 || maximumRounds > 32
                || islandMaxArea < 16 || islandMaxArea > 88209) throw new IllegalArgumentException("积分补偿限额无效");
        resourceWeights = Map.copyOf(resourceWeights); penalties = Map.copyOf(penalties); structureWeights = Map.copyOf(structureWeights);
        if (!structureWeights.containsKey("default")) throw new IllegalArgumentException("遗迹默认积分缺失");
        for (Resource resource : Resource.values()) check(resourceWeights.getOrDefault(resource, Double.NaN), 0.0001, 100, resource.name());
        for (TerrainKind kind : TerrainKind.values()) check(penalties.getOrDefault(kind, Double.NaN), 0, 100, kind.name());
        structureWeights.forEach((key, value) -> check(value, 0, 1000, key));
    }
    private static void check(double value, double minimum, double maximum, String name) {
        if (!Double.isFinite(value) || value < minimum || value > maximum) throw new IllegalArgumentException("积分配置无效：" + name);
    }
    public static ScoreSettings defaults() { return from(new YamlConfiguration()); }
    public static ScoreSettings from(ConfigurationSection config) {
        String path = "map.scoring.";
        EnumMap<Resource, Double> resources = new EnumMap<>(Resource.class);
        double[] weights = {0.02, 0.02, 0.04, 0.06, 0.16};
        for (Resource resource : Resource.values()) resources.put(resource,
                config.getDouble(path + "resources." + resource.name().toLowerCase(java.util.Locale.ROOT), weights[resource.ordinal()]));
        EnumMap<TerrainKind, Double> penalties = new EnumMap<>(TerrainKind.class);
        double[] costs = {3, 4, 6, 4, 5};
        for (TerrainKind kind : TerrainKind.values()) penalties.put(kind,
                config.getDouble(path + "penalties-per-1000." + kind.name().toLowerCase(java.util.Locale.ROOT), costs[kind.ordinal()]));
        Map<String, Double> structures = new TreeMap<>(Map.ofEntries(
                Map.entry("default", 80.0), Map.entry("village", 160.0), Map.entry("mansion", 260.0),
                Map.entry("ancient_city", 280.0), Map.entry("trial_chambers", 200.0), Map.entry("mineshaft", 80.0),
                Map.entry("buried_treasure", 40.0), Map.entry("ruined_portal", 60.0), Map.entry("desert_pyramid", 120.0),
                Map.entry("jungle_pyramid", 120.0), Map.entry("shipwreck", 80.0), Map.entry("ocean_ruin", 60.0),
                Map.entry("pillager_outpost", 120.0), Map.entry("stronghold", 200.0), Map.entry("monument", 220.0),
                Map.entry("swamp_hut", 60.0), Map.entry("igloo", 60.0)));
        structures.putAll(OriginalRuinCatalog.DEFAULT_POINTS);
        ConfigurationSection section = config.getConfigurationSection(path + "structures");
        if (section != null) for (String key : section.getKeys(false)) structures.put(key, section.getDouble(key));
        return new ScoreSettings(config.getDouble(path + "base", 1000), config.getDouble(path + "land-per-1000", 3),
                config.getDouble(path + "contiguous-land-per-1000", 5), config.getDouble(path + "exposed-extra-multiplier", 1.5),
                config.getDouble(path + "relative-tolerance", 0.05), config.getDouble(path + "absolute-tolerance", 5),
                config.getDouble(path + "minimum-land-fraction", 0.20), config.getInt(path + "minimum-wood", 512),
                config.getInt(path + "difficult-terrain-wood", 1024), config.getInt(path + "maximum-forest-logs", 2048),
                config.getInt(path + "maximum-ruins", 8), config.getInt(path + "maximum-ore-per-resource", 2048),
                config.getInt(path + "maximum-rounds", 12), config.getDouble(path + "maximum-round-points", 240),
                config.getDouble(path + "compensation-ruin-points", 80), config.getInt(path + "island-max-area", 16384),
                resources, penalties, structures);
    }
    public double tolerance(double median) { return Math.max(absoluteTolerance, Math.abs(median) * relativeTolerance); }
    public double structurePoints(String kind) {
        if (kind.equals("warwalltool:compensation_ruin")) return ruinPoints;
        Double qualified = structureWeights.get(kind);
        if (qualified != null) return qualified;
        if (kind.contains(":") && !kind.startsWith("minecraft:")) return structureWeights.get("default");
        String key = kind.substring(kind.indexOf(':') + 1);
        Double exact = structureWeights.get(key);
        if (exact != null) return exact;
        for (String family : new String[]{"village", "mineshaft", "ruined_portal", "ocean_ruin", "shipwreck"})
            if (key.startsWith(family + "_")) return structureWeights.getOrDefault(family, structureWeights.get("default"));
        return structureWeights.get("default");
    }
    public String signature() {
        StringBuilder result = new StringBuilder("score-v1;").append(baseScore).append(';').append(landPer1000).append(';')
                .append(contiguousPer1000).append(';').append(exposedMultiplier).append(';').append(relativeTolerance).append(';')
                .append(absoluteTolerance).append(';').append(minimumLand).append(';').append(minimumWood).append(';')
                .append(difficultWood).append(';').append(maximumForestLogs).append(';').append(maximumRuins).append(';')
                .append(maximumOrePerResource).append(';').append(maximumRounds).append(';').append(maximumRoundPoints).append(';')
                .append(ruinPoints).append(';').append(islandMaxArea);
        for (Resource resource : Resource.values()) result.append(';').append(resource).append('=').append(resourceWeights.get(resource));
        for (TerrainKind kind : TerrainKind.values()) result.append(';').append(kind).append('=').append(penalties.get(kind));
        new TreeMap<>(structureWeights).forEach((key, value) -> result.append(';').append(key).append('=').append(value));
        return result.toString();
    }
}

