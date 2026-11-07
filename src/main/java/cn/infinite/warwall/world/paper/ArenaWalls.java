package cn.infinite.warwall.world.paper;

import cn.infinite.warwall.world.room.WarwallMode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

public final class ArenaWalls {
    private final Plugin plugin;
    private final World world;
    private boolean busy;
    public ArenaWalls(Plugin plugin, World world) { this.plugin = plugin; this.world = world; }
    public void apply(WarwallMode mode, Consumer<Throwable> completed) {
        if (busy) throw new IllegalStateException("已有墙体布置操作，请等它完成。");
        busy = true; next(ArenaWallPlan.forMode(mode), 0, completed);
    }
    private void next(List<ArenaWallPlan.Segment> plan, int index, Consumer<Throwable> completed) {
        if (!plugin.isEnabled()) { busy = false; return; }
        if (index == plan.size()) { busy = false; completed.accept(null); return; }
        ArenaWallPlan.Segment segment = plan.get(index);
        List<CompletableFuture<Chunk>> loading = new ArrayList<>();
        for (int x = Math.floorDiv(segment.x1(), 16); x <= Math.floorDiv(segment.x2(), 16); x++)
            for (int z = Math.floorDiv(segment.z1(), 16); z <= Math.floorDiv(segment.z2(), 16); z++) loading.add(world.getChunkAtAsync(x, z, true));
        CompletableFuture.allOf(loading.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                List<Chunk> tickets = new ArrayList<>();
                try {
                    if (error != null) throw new IllegalStateException("墙体区块加载失败。", error);
                    for (var future : loading) { Chunk chunk = future.join(); if (chunk.addPluginChunkTicket(plugin)) tickets.add(chunk); }
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), segment.command());
                    var mismatch = segment.firstMismatch((x, y, z) -> world.getBlockAt(x, y, z).getType() == Material.BEDROCK);
                    if (mismatch != null) throw new IllegalStateException("墙体复查未通过，已中止开局：位置="
                            + mismatch.x() + "," + mismatch.y() + "," + mismatch.z() + "，操作=" + (segment.build() ? "建墙" : "清除旧墙"));
                } catch (Throwable exception) { busy = false; completed.accept(exception); return; }
                finally { for (Chunk chunk : tickets) chunk.removePluginChunkTicket(plugin); }
                next(plan, index + 1, completed);
            });
        });
    }
}

