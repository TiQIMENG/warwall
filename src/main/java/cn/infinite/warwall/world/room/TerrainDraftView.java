package cn.infinite.warwall.world.room;

import java.util.*;

/** Read-only snapshot of the datapack state. It never draws an order or advances a turn. */
public record TerrainDraftView(int phase, Set<UUID> candidates, Set<UUID> confirmed,
                               Map<UUID, Integer> order, Map<CombatTeam, UUID> claims) {
    public static final TerrainDraftView EMPTY = new TerrainDraftView(0, Set.of(), Set.of(), Map.of(), Map.of());
    public TerrainDraftView {
        if (phase < 0 || phase > 4) throw new IllegalArgumentException("无效的地形分队阶段。");
        candidates = Set.copyOf(candidates); confirmed = Set.copyOf(confirmed);
        order = Map.copyOf(order); claims = Map.copyOf(claims);
        if (!candidates.containsAll(confirmed) || !candidates.containsAll(order.keySet()) || !candidates.containsAll(claims.values()))
            throw new IllegalArgumentException("地形分队快照包含未知队长。");
        if (new HashSet<>(order.values()).size() != order.size() || order.values().stream().anyMatch(i -> i < 1 || i > 4)
                || new HashSet<>(claims.values()).size() != claims.size()) throw new IllegalArgumentException("地形分队顺序或领地重复。");
        if (phase >= 2 && (candidates.size() != 4 || confirmed.size() != 4 || order.size() != 4))
            throw new IllegalArgumentException("四名队长确认后才能选地形。");
        if (phase >= 3 && claims.size() != 4) throw new IllegalArgumentException("四块地形选齐后才能选队员。");
    }
    public boolean complete() { return phase == 4; }
    public List<UUID> terrainOrder() { return order.entrySet().stream().sorted(Map.Entry.comparingByValue()).map(Map.Entry::getKey).toList(); }
    public List<UUID> playerOrder() { return terrainOrder().reversed(); }
    public String display() { return switch (phase) { case 1 -> "等待四名队长确认"; case 2 -> "队长选择地形"; case 3 -> "反向顺序选择队员"; case 4 -> "地形和队员已选齐"; default -> "尚未开始地形分队"; }; }
}

