package cn.infinite.warwall.world.paper;

import cn.infinite.warwall.world.room.WarwallMode;
import java.util.ArrayList;
import java.util.List;

/** 墙体操作只覆盖原生分隔线，既不移动地图中央，也不接触高空大厅。 */
public final class ArenaWallPlan {
    public static final int MIN_Y = -63, MAX_Y = 150;
    private ArenaWallPlan() { }
    public record Point(int x, int y, int z) { }
    @FunctionalInterface public interface BlockReader { boolean isBedrock(int x, int y, int z); }
    public record Segment(int x1, int z1, int x2, int z2, boolean build) {
        public Segment {
            if (x1 > x2 || z1 > z2 || x1 < -300 || z1 < -300 || x2 > 300 || z2 > 300
                    || (x2 - x1 + 1L) * (z2 - z1 + 1) * (MAX_Y - MIN_Y + 1) > 32768) throw new IllegalArgumentException("墙体操作超出适配范围。");
        }
        public String command() {
            return "execute in minecraft:overworld run fill " + x1 + " " + MIN_Y + " " + z1 + " " + x2 + " " + MAX_Y + " " + z2
                    + (build ? " minecraft:bedrock" : " minecraft:air replace minecraft:bedrock");
        }
        public Point firstMismatch(BlockReader reader) {
            // 角点正确不代表整段墙完整；清墙只要求旧基岩消失，不要求天然地形变成空气。
            for (int x = x1; x <= x2; x++) for (int z = z1; z <= z2; z++)
                for (int y = MIN_Y; y <= MAX_Y; y++) if (reader.isBedrock(x, y, z) != build) return new Point(x, y, z);
            return null;
        }
    }
    public static List<Segment> forMode(WarwallMode mode) {
        List<Segment> result = new ArrayList<>();
        for (int chunk = -19; chunk <= 18; chunk++) {
            int low = Math.max(-300, chunk * 16), high = Math.min(300, chunk * 16 + 15);
            result.add(new Segment(-1, low, 1, high, true));
        }
        for (int chunk = -19; chunk <= 18; chunk++) {
            int low = Math.max(-300, chunk * 16), high = Math.min(300, chunk * 16 + 15);
            if (mode == WarwallMode.NORMAL) result.add(new Segment(low, -1, high, 1, true));
            else {
                if (low <= -2) result.add(new Segment(low, -1, Math.min(-2, high), 1, false));
                if (high >= 2) result.add(new Segment(Math.max(2, low), -1, high, 1, false));
            }
        }
        return List.copyOf(result);
    }
}

