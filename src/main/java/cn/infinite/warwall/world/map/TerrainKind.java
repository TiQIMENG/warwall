package cn.infinite.warwall.world.map;

import java.util.EnumSet;
import java.util.Locale;

/** Penalties may overlap: snowy peaks are both snow and mountain terrain. */
public enum TerrainKind {
    SNOW, DESERT, ISLAND, BADLANDS, MOUNTAIN;

    public static EnumSet<TerrainKind> at(String biome, int groundY) {
        String key = biome.toLowerCase(Locale.ROOT);
        EnumSet<TerrainKind> result = EnumSet.noneOf(TerrainKind.class);
        if (key.contains("snow") || key.contains("frozen") || key.contains("ice_spikes")
                || key.endsWith(":grove") || key.contains("jagged_peaks")) result.add(SNOW);
        if (key.endsWith(":desert")) result.add(DESERT);
        if (key.contains("badlands")) result.add(BADLANDS);
        if (groundY > 120 || key.contains("peaks") || key.contains("windswept")
                || key.contains("mountain")) result.add(MOUNTAIN);
        return result;
    }
}

