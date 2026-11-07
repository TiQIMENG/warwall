package cn.infinite.warwall.world.map;

import java.util.LinkedHashMap;
import java.util.Map;

/** Stores counters, not snapshots. Rescanning a dirty chunk replaces rather than adds its counters. */
public final class ChunkMetricsIndex {
    private final Map<ArenaGeometry.ChunkPosition, ResourceMetrics> chunks = new LinkedHashMap<>();
    public void replace(ArenaGeometry.ChunkPosition position, ResourceMetrics metrics) { chunks.put(position, metrics); }
    public int size() { return chunks.size(); }
    public void clear() { chunks.clear(); }
    public ResourceMetrics totals() {
        ResourceMetrics totals = new ResourceMetrics();
        chunks.values().forEach(totals::merge);
        return totals;
    }
}

