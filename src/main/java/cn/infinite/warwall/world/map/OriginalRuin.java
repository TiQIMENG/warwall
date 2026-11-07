package cn.infinite.warwall.world.map;

import cn.infinite.warwall.world.room.CombatTeam;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Function;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.loot.Lootable;
import org.bukkit.structure.Structure;

/** Loads the installed datapack template and retains its block NBT and lazy loot table. */
public final class OriginalRuin {
    public record Block(int x, int y, int z, Material material, String lootTable) { }
    public record Template(OriginalRuinCatalog.Spec spec, Structure structure, List<Block> blocks) {
        public Template { blocks = List.copyOf(blocks); }
    }
    public record Placed(CombatTeam team, int x, int y, int z, Template template) {
        public TerritoryStructure metric() {
            var spec = template.spec();
            return new TerritoryStructure("original-compensation:" + spec.key() + ":" + x + ":" + y + ":" + z,
                    spec.key(), team, x, z, x + spec.sizeX() - 1, z + spec.sizeZ() - 1, 1, y - 1, y + spec.sizeY() - 1);
        }
    }
    private OriginalRuin() { }
    public static Template load(OriginalRuinCatalog.Spec spec) {
        Structure structure = Bukkit.getStructureManager().loadStructure(NamespacedKey.fromString(spec.key()));
        if (structure == null) throw new IllegalStateException("原创遗迹模板不可用，请启用对应 wallwar 数据包：" + spec.key());
        var size = structure.getSize();
        if (size.getBlockX() != spec.sizeX() || size.getBlockY() != spec.sizeY() || size.getBlockZ() != spec.sizeZ()
                || structure.getPaletteCount() != 1 || structure.getEntityCount() != 0)
            throw new IllegalStateException("原创遗迹模板版本或尺寸不兼容，需重新核对：" + spec.key());
        List<Block> expected = new ArrayList<>();
        for (BlockState state : structure.getPalettes().getFirst().getBlocks()) {
            String loot = state instanceof Lootable lootable && lootable.getLootTable() != null ? lootable.getLootTable().getKey().toString() : null;
            expected.add(new Block(state.getX(), state.getY(), state.getZ(), state.getType(), loot));
        }
        validate(spec, expected);
        if (!fingerprint(expected).equals(OriginalRuinCatalog.MATERIAL_FINGERPRINTS.get(spec.key())))
            throw new IllegalStateException("原创遗迹方块布局与已核对版本不一致：" + spec.key());
        if (spec.lootTable() != null && Bukkit.getLootTable(NamespacedKey.fromString(spec.lootTable())) == null)
            throw new IllegalStateException("原创遗迹战利品表未注册：" + spec.lootTable());
        return new Template(spec, structure, expected);
    }
    public static void validate(OriginalRuinCatalog.Spec spec, List<Block> blocks) {
        if (!spec.compensate() || blocks.isEmpty()) throw new IllegalArgumentException("不适合作为地表补偿遗迹");
        boolean lootPresent = spec.lootTable() == null;
        java.util.Set<String> positions = new java.util.HashSet<>();
        for (Block block : blocks) {
            if (block.x() < 0 || block.x() >= spec.sizeX() || block.y() < 0 || block.y() >= spec.sizeY()
                    || block.z() < 0 || block.z() >= spec.sizeZ() || !positions.add(block.x() + ":" + block.y() + ":" + block.z()))
                throw new IllegalStateException("原创遗迹方块偏移无效：" + spec.key());
            if (block.material() == Material.JIGSAW || block.material() == Material.STRUCTURE_BLOCK || block.material() == Material.STRUCTURE_VOID
                    || block.material() == Material.COMMAND_BLOCK || block.material() == Material.CHAIN_COMMAND_BLOCK || block.material() == Material.REPEATING_COMMAND_BLOCK)
                throw new IllegalStateException("地表模板包含未审核处理器或命令方块：" + spec.key());
            if (block.lootTable() != null && !block.lootTable().equals(spec.lootTable()))
                throw new IllegalStateException("原创遗迹战利品表不匹配：" + spec.key());
            lootPresent |= spec.lootTable() != null && spec.lootTable().equals(block.lootTable());
        }
        if (!lootPresent) throw new IllegalStateException("原创遗迹缺少原生奖励容器：" + spec.key());
    }
    public static String fingerprint(List<Block> blocks) {
        String text = blocks.stream().sorted(java.util.Comparator.comparingInt(Block::x).thenComparingInt(Block::y).thenComparingInt(Block::z))
                .map(block -> block.x() + "," + block.y() + "," + block.z() + ":" + block.material().name() + ":" + (block.lootTable() == null ? "" : block.lootTable()))
                .collect(java.util.stream.Collectors.joining("\n"));
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    public static List<Runnable> jobs(World world, Placed ruin, Random random, Runnable completed) {
        List<Runnable> jobs = new ArrayList<>(); var spec = ruin.template().spec();
        for (int dx = 0; dx < spec.sizeX(); dx++) for (int dz = 0; dz < spec.sizeZ(); dz++) {
            int x = ruin.x() + dx, z = ruin.z() + dz;
            jobs.add(() -> {
                if (!world.getBlockAt(x, ruin.y() - 1, z).getType().isSolid()) world.getBlockAt(x, ruin.y() - 1, z).setType(Material.DIRT, false);
                for (int y = ruin.y(); y < ruin.y() + spec.sizeY(); y++) world.getBlockAt(x, y, z).setType(Material.AIR, false);
            });
        }
        jobs.add(() -> {
            ruin.template().structure().place(new Location(world, ruin.x(), ruin.y(), ruin.z()), true,
                    StructureRotation.NONE, Mirror.NONE, 0, 1, random);
            if (!verify(world, ruin)) throw new IllegalStateException("原创遗迹实物或战利品表复查失败：" + spec.key());
            completed.run();
        });
        return List.copyOf(jobs);
    }
    public static boolean verify(World world, Placed ruin) {
        var spec = ruin.template().spec();
        for (int dx = 0; dx < spec.sizeX(); dx++) for (int dz = 0; dz < spec.sizeZ(); dz++)
            if (!world.getBlockAt(ruin.x() + dx, ruin.y() - 1, ruin.z() + dz).getType().isSolid()) return false;
        return matches(ruin.template(), block -> world.getBlockAt(ruin.x() + block.x(), ruin.y() + block.y(), ruin.z() + block.z()).getType(), block -> {
            var state = world.getBlockAt(ruin.x() + block.x(), ruin.y() + block.y(), ruin.z() + block.z()).getState();
            return state instanceof Lootable lootable && lootable.getLootTable() != null ? lootable.getLootTable().getKey().toString() : null;
        });
    }
    public static boolean matches(Template template, Function<Block, Material> material, Function<Block, String> lootTable) {
        for (Block block : template.blocks()) {
            Material actual = material.apply(block);
            if (actual != block.material() && !(SnapshotScanner.air(block.material()) && actual == Material.SNOW)) return false;
            // Do not read inventory: preserve the original lazy random rewards without triggering a roll.
            if (block.lootTable() != null && !block.lootTable().equals(lootTable.apply(block))) return false;
        }
        return true;
    }
}

