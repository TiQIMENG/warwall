package cn.infinite.warwall.arena;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link Box} 的边界行为：归一化、包含判定、体积、相交。 */
class BoxTest {

    @Test
    @DisplayName("构造时把任意两个角归一化成 min/max")
    void normalizesCorners() {
        Box box = Box.of("world", 10, 20, 30, 1, 2, 3);
        assertEquals(1, box.minX());
        assertEquals(2, box.minY());
        assertEquals(3, box.minZ());
        assertEquals(10, box.maxX());
        assertEquals(20, box.maxY());
        assertEquals(30, box.maxZ());
    }

    @Test
    @DisplayName("体积含端点：单方块是 1，2×2×2 是 8")
    void volume() {
        assertEquals(1L, Box.of("w", 0, 0, 0, 0, 0, 0).volume());
        assertEquals(8L, Box.of("w", 0, 0, 0, 1, 1, 1).volume());
    }

    @Test
    @DisplayName("包含判定把边界算在内")
    void containsInclusive() {
        Box box = Box.of("w", 0, 0, 0, 2, 2, 2);
        assertTrue(box.contains(0, 0, 0));
        assertTrue(box.contains(2, 2, 2));
        assertTrue(box.contains(1, 1, 1));
        assertFalse(box.contains(3, 1, 1));
        assertFalse(box.contains(1, -1, 1));
    }

    @Test
    @DisplayName("相交：不同世界恒不相交；贴边也算相交")
    void intersects() {
        Box a = Box.of("w", 0, 0, 0, 5, 5, 5);
        Box b = Box.of("w", 5, 5, 5, 10, 10, 10);
        Box c = Box.of("w", 6, 6, 6, 10, 10, 10);
        Box other = Box.of("w2", 0, 0, 0, 5, 5, 5);

        assertTrue(a.intersects(b), "只共用一个角也算相交");
        assertFalse(a.intersects(c), "隔开一格就不相交");
        assertFalse(a.intersects(other), "不同世界不相交");
        assertFalse(a.intersects(null));
    }

    @Test
    @DisplayName("中心点按含端点计算")
    void center() {
        Box box = Box.of("w", 0, 0, 0, 3, 3, 3);
        assertEquals(2.0, box.centerX(), 1e-9);
    }
}
