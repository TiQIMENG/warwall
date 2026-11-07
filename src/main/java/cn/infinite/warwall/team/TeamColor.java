package cn.infinite.warwall.team;

import java.util.Locale;
import org.bukkit.ChatColor;
import org.bukkit.Material;

/**
 * 四支队伍。顺序即分队与侧边栏排列顺序，改动会影响 {@code ordinal()} 取值，不要随意重排。
 *
 * <p>颜色与羊毛材质用于出生点平台、凋零基座与侧边栏着色；全部是原版物品，不依赖资源包。
 */
public enum TeamColor {

    RED("red", "红队", ChatColor.RED, Material.RED_WOOL, Material.RED_DYE, "凋零·红"),
    YELLOW("yellow", "黄队", ChatColor.YELLOW, Material.YELLOW_WOOL, Material.YELLOW_DYE, "凋零·黄"),
    GREEN("green", "绿队", ChatColor.GREEN, Material.GREEN_WOOL, Material.GREEN_DYE, "凋零·绿"),
    BLUE("blue", "蓝队", ChatColor.BLUE, Material.BLUE_WOOL, Material.BLUE_DYE, "凋零·蓝");

    private final String id;
    private final String chineseName;
    private final ChatColor chatColor;
    private final Material wool;
    private final Material dye;
    private final String witherName;

    TeamColor(String id, String chineseName, ChatColor chatColor,
              Material wool, Material dye, String witherName) {
        this.id = id;
        this.chineseName = chineseName;
        this.chatColor = chatColor;
        this.wool = wool;
        this.dye = dye;
        this.witherName = witherName;
    }

    /** 配置键 / 命令参数用的稳定标识。 */
    public String id() {
        return id;
    }

    public String chineseName() {
        return chineseName;
    }

    public ChatColor chatColor() {
        return chatColor;
    }

    public Material wool() {
        return wool;
    }

    public Material dye() {
        return dye;
    }

    public String witherName() {
        return witherName;
    }

    /** 带颜色的队伍名，直接拼进聊天消息。 */
    public String coloredName() {
        return chatColor + chineseName + ChatColor.RESET;
    }

    /** 宽松解析：支持 red / RED / 红 / 红队，未知返回 null。 */
    public static TeamColor parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        for (TeamColor team : values()) {
            if (team.id.equals(key)
                    || team.name().toLowerCase(Locale.ROOT).equals(key)
                    || team.chineseName.equals(raw.trim())
                    || team.chineseName.replace("队", "").equals(raw.trim())) {
                return team;
            }
        }
        return null;
    }
}
