package cn.infinite.warwall.world;

import java.util.ArrayDeque;
import java.util.Deque;
import org.bukkit.World;

/**
 * 陆地连通性检查。
 *
 * <p>参考 WarwallTool 的 {@code map/LandTopology}：随机地形最大的风险不是难看，而是
 * **某个队伍的出生点被地形或墙围死**——开局就出不去，这局直接废掉。
 *
 * <p>做法：先算一张高度图（每列最高的非空气方块），相邻列高度差 ≤1 视为可走，
 * 然后从出生点做 BFS，检查两件事：
 * <ol>
 *   <li>能不能走到地图中心</li>
 *   <li>可走面积占总面积的比例够不够（默认 ≥18%）</li>
 * </ol>
 */
public final class LandTopology {

    /** 相邻列允许的最大高度差。1 格是原版能直接走上去的台阶。 */
    private static final int MAX_STEP = 1;

    private LandTopology() {
    }

    /** 检查结果。 */
    public record Result(boolean reachable, double ratio, int reachableColumns) {
    }

    /**
     * 从出生点做一次 BFS。
     *
     * @param radius 地图半径（以中心为原点的正方形范围）
     */
    public static Result check(World world, int centerX, int centerZ, int radius,
                               int startX, int startZ, double minRatio) {
        int side = radius * 2 + 1;
        int[][] height = new int[side][side];
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                height[dx + radius][dz + radius] = topSolidY(world, centerX + dx, centerZ + dz);
            }
        }

        boolean[][] visited = new boolean[side][side];
        int startDx = clamp(startX - centerX + radius, 0, side - 1);
        int startDz = clamp(startZ - centerZ + radius, 0, side - 1);
        int startHeight = height[startDx][startDz];

        Deque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[] {startDx, startDz});
        visited[startDx][startDz] = true;
        int count = 0;
        boolean reachedCenter = false;
        int centerIndex = radius;

        while (!queue.isEmpty()) {
            int[] current = queue.poll();
            int cx = current[0];
            int cz = current[1];
            count++;
            if (Math.abs(cx - centerIndex) <= 3 && Math.abs(cz - centerIndex) <= 3) {
                reachedCenter = true;
            }
            int currentHeight = height[cx][cz];
            int[][] neighbors = {{cx + 1, cz}, {cx - 1, cz}, {cx, cz + 1}, {cx, cz - 1}};
            for (int[] next : neighbors) {
                int nx = next[0];
                int nz = next[1];
                if (nx < 0 || nz < 0 || nx >= side || nz >= side || visited[nx][nz]) {
                    continue;
                }
                int nextHeight = height[nx][nz];
                // 高度差超过 1 格走不过去；低于世界底部视为虚空
                if (Math.abs(nextHeight - currentHeight) > MAX_STEP) {
                    continue;
                }
                if (nextHeight <= world.getMinHeight()) {
                    continue;
                }
                visited[nx][nz] = true;
                queue.add(new int[] {nx, nz});
            }
        }

        double total = (double) side * side;
        double ratio = count / total;
        boolean ok = reachedCenter && ratio >= minRatio;
        return new Result(ok, ratio, count);
    }

    /** 某列最高的非空气方块 Y；整列是空气则返回世界底部。 */
    private static int topSolidY(World world, int x, int z) {
        for (int y = world.getMaxHeight() - 1; y > world.getMinHeight(); y--) {
            if (!world.getBlockAt(x, y, z).getType().isAir()) {
                return y;
            }
        }
        return world.getMinHeight();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
