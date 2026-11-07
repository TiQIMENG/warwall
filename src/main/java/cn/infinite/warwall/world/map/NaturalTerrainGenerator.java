package cn.infinite.warwall.world.map;

import org.bukkit.generator.ChunkGenerator;

/** 保留原版地形、装饰和自然结构；不同领地的收益交由综合积分平衡。 */
public final class NaturalTerrainGenerator extends ChunkGenerator {
    @Override public boolean shouldGenerateNoise() { return true; }
    @Override public boolean shouldGenerateSurface() { return true; }
    @Override public boolean shouldGenerateBedrock() { return true; }
    @Override public boolean shouldGenerateCaves() { return true; }
    @Override public boolean shouldGenerateDecorations() { return true; }
    @Override public boolean shouldGenerateMobs() { return true; }
    @Override public boolean shouldGenerateStructures() { return true; }
    @Override public boolean isParallelCapable() { return true; }
}

