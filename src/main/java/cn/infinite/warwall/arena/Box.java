// 纯逻辑类：不允许 import org.bukkit。
package cn.infinite.warwall.arena;

/**
 * 轴对齐长方体选区，坐标全部是方块坐标（含端点）。
 *
 * <p>世界名单独保存，这样 {@code Box} 可以在没有世界加载的情况下参与校验与单元测试。
 */
public record Box(String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public Box {
        // 允许调用方给任意两个角，这里统一成 min/max，避免后续处处比大小。
        int x1 = Math.min(minX, maxX);
        int y1 = Math.min(minY, maxY);
        int z1 = Math.min(minZ, maxZ);
        int x2 = Math.max(minX, maxX);
        int y2 = Math.max(minY, maxY);
        int z2 = Math.max(minZ, maxZ);
        minX = x1;
        minY = y1;
        minZ = z1;
        maxX = x2;
        maxY = y2;
        maxZ = z2;
    }

    public static Box of(String world, int x1, int y1, int z1, int x2, int y2, int z2) {
        return new Box(world, x1, y1, z1, x2, y2, z2);
    }

    /** 方块数量（含端点）。 */
    public long volume() {
        return (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    /** 两个盒子是否相交（不同世界恒为 false）。 */
    public boolean intersects(Box other) {
        if (other == null || !sameWorld(other)) {
            return false;
        }
        return minX <= other.maxX && maxX >= other.minX
                && minY <= other.maxY && maxY >= other.minY
                && minZ <= other.maxZ && maxZ >= other.minZ;
    }

    public boolean sameWorld(Box other) {
        return other != null && world != null && world.equalsIgnoreCase(other.world);
    }

    public double centerX() {
        return (minX + maxX + 1) / 2.0;
    }

    public double centerY() {
        return (minY + maxY + 1) / 2.0;
    }

    public double centerZ() {
        return (minZ + maxZ + 1) / 2.0;
    }

    /** 人类可读形式，用于命令回显。 */
    public String describe() {
        return world + " (" + minX + "," + minY + "," + minZ + ") → (" + maxX + "," + maxY + "," + maxZ + ")"
                + " 共 " + volume() + " 方块";
    }
}
