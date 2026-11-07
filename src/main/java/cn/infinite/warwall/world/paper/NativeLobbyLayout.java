package cn.infinite.warwall.world.paper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;

/** 只读取原生建墙函数中的高空大厅方块命令，不执行计分板、建墙或其他初始化。 */
public final class NativeLobbyLayout {
    public record Point(int x, int y, int z) { }
    public record ChunkPoint(int x, int z) { }
    private static final Pattern FILL = Pattern.compile("^(?:execute positioned (~(?:-?\\d+)?) ~ ~ run )?fill (~(?:-?\\d+)?) (\\d+) (~(?:-?\\d+)?) (~(?:-?\\d+)?) (\\d+) (~(?:-?\\d+)?) (?:minecraft:)?([a-z_]+)$");
    private static final Set<String> MATERIALS = Set.of("birch_planks", "glass", "air", "smoker", "blast_furnace", "crafting_table");
    private final List<String> commands;
    private final Map<Point, String> blocks;
    private final Set<ChunkPoint> chunks;

    private NativeLobbyLayout(List<String> commands, Map<Point, String> blocks) {
        this.commands = List.copyOf(commands);
        this.blocks = Map.copyOf(blocks);
        Set<ChunkPoint> positions = new LinkedHashSet<>();
        for (Point point : blocks.keySet()) positions.add(new ChunkPoint(Math.floorDiv(point.x(), 16), Math.floorDiv(point.z(), 16)));
        this.chunks = Set.copyOf(positions);
    }

    public static NativeLobbyLayout parse(String source) {
        if (source.length() > 131072) throw new IllegalArgumentException("原生建墙函数超出读取上限。");
        List<String> commands = new ArrayList<>();
        Map<Point, String> blocks = new LinkedHashMap<>();
        Set<String> materials = new LinkedHashSet<>();
        int edits = 0;
        for (String raw : source.split("\\R")) {
            String line = raw.strip();
            var match = FILL.matcher(line);
            if (!match.matches()) continue;
            int y1 = Integer.parseInt(match.group(3)), y2 = Integer.parseInt(match.group(6));
            int minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
            if (minY < 250 || maxY > 300) continue;
            int offset = relative(match.group(1));
            int x1 = Math.addExact(offset, relative(match.group(2))), x2 = Math.addExact(offset, relative(match.group(5)));
            int z1 = relative(match.group(4)), z2 = relative(match.group(7));
            String material = match.group(8);
            if (x1 < -15 || x1 > 15 || x2 < -15 || x2 > 15 || z1 < -15 || z1 > 15 || z2 < -15 || z2 > 15 || !MATERIALS.contains(material)) {
                throw new IllegalArgumentException("原生大厅命令超出已适配的区域或方块范围。");
            }
            long volume = (Math.abs(x1 - x2) + 1L) * (maxY - minY + 1L) * (Math.abs(z1 - z2) + 1L);
            if (volume + edits > 16384 || commands.size() >= 16) throw new IllegalArgumentException("原生大厅方块数量超出修补上限。");
            edits += (int) volume;
            for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) for (int y = minY; y <= maxY; y++) for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                blocks.put(new Point(x, y, z), material);
            }
            materials.add(material); commands.add(line);
        }
        if (!materials.equals(MATERIALS)
                || !"glass".equals(blocks.get(new Point(0, 250, 0)))
                || !"crafting_table".equals(blocks.get(new Point(0, 251, 0)))
                || !"air".equals(blocks.get(new Point(0, 252, 0)))
                || !"air".equals(blocks.get(new Point(0, 253, 0)))) {
            throw new IllegalArgumentException("原生大厅布局不完整或版本不受支持，不能开放房间。");
        }
        for (int x : new int[]{-15, 15}) for (int z : new int[]{-15, 15}) {
            if (!"birch_planks".equals(blocks.get(new Point(x, 250, z))) || !"birch_planks".equals(blocks.get(new Point(x, 254, z)))) {
                throw new IllegalArgumentException("原生大厅边界不完整，不能开放房间。");
            }
        }
        return new NativeLobbyLayout(commands, blocks);
    }

    private static int relative(String value) { return value == null || value.equals("~") ? 0 : Integer.parseInt(value.substring(1)); }
    public List<String> commands() { return commands; }
    public Map<Point, String> blocks() { return blocks; }
    public Set<ChunkPoint> chunks() { return chunks; }
    public boolean matches(Function<Point, String> reader) { return blocks.entrySet().stream().allMatch(entry -> entry.getValue().equals(reader.apply(entry.getKey()))); }
    public boolean repairIfMissing(Consumer<String> executor, Function<Point, String> reader) {
        if (matches(reader)) return false;
        commands.forEach(executor);
        if (!matches(reader)) throw new IllegalStateException("原生战墙大厅未生成完整，房间仍保持关闭，请检查区块和数据包命令。");
        return true;
    }
}

