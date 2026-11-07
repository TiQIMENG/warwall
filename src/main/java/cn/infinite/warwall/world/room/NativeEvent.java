package cn.infinite.warwall.world.room;

import java.util.Arrays;
import java.util.List;

/** ID 是已安装数据包的事件池接口；不接受菜单外传入的 NBT 路径。 */
public enum NativeEvent {
    COMMAND("start", "command", "无限火火火火火力"),
    WITHER("start", "wither", "往日回忆"),
    SMALL("start", "small", "矿工国度"),
    OHDREAM("start", "ohdream", "极限追杀"),
    NODIE("start", "nodie", "自杀禁止日"),
    ARMOR_DAMAGE("norm", "armor_damage", "食戟之灵"),
    DROPHEAD("norm", "drophead", "甲级战犯"),
    GHOST("norm", "ghost", "月光光心慌慌"),
    PIG("norm", "pig", "猪会飞！？"),
    SKELETON("norm", "skeleton", "惊悚恐怖骷髅"),
    RESPAWN("norm", "respawn", "浴火重生"),
    WATER("norm", "water", "水是剧毒的"),
    FOOTBALL("norm", "football", "圣诞节的足球"),
    INVISIBLE("norm", "invisible", "透明人间"),
    SPACE("norm", "space", "太空人"),
    UNSAFE_FALL("norm", "unsafe_fall", "猎魔人"),
    GIFT("norm", "gift", "空投"),
    SUNNINE("norm", "sunnine", "九个太阳"),
    VOTE("special", "vote", "人道主义关怀"),
    WITHER_SP("special", "wither_sp", "凋零议会");
    private final String pool, id, display;
    NativeEvent(String pool, String id, String display) { this.pool = pool; this.id = id; this.display = display; }
    public String pool() { return pool; }
    public String id() { return id; }
    public String display() { return display; }
    public String key() { return pool + "/" + id; }
    public String category() { return switch (pool) { case "start" -> "开局事件"; case "norm" -> "阶段事件"; default -> "特殊事件"; }; }
    public static NativeEvent parse(String key) {
        return Arrays.stream(values()).filter(event -> event.key().equals(key)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知事件，请从事件菜单选择。"));
    }
    public static List<String> keys() { return Arrays.stream(values()).map(NativeEvent::key).toList(); }
}

