package cn.infinite.warwall.world.room;

import java.util.Arrays;
import java.util.Locale;

public enum CombatTeam {
    RED("red", "红队", 4, 1, 1),
    YELLOW("yellow", "黄队", 3, -1, 1),
    BLUE("blue", "蓝队", 1, 1, -1),
    GREEN("green", "绿队", 2, -1, -1);

    private final String key;
    private final String display;
    private final int tid, signX, signZ;
    CombatTeam(String key, String display, int tid, int signX, int signZ) {
        this.key = key; this.display = display; this.tid = tid;
        this.signX = signX; this.signZ = signZ;
    }
    public String key() { return key; }
    public String display() { return display; }
    public int tid() { return tid; }
    public int signX() { return signX; }
    public int signZ() { return signZ; }
    public static CombatTeam parse(String value) {
        return Arrays.stream(values()).filter(team -> team.key.equals(value.toLowerCase(Locale.ROOT))
                || team.display.equals(value)).findFirst().orElseThrow(() -> new IllegalArgumentException("未知队伍，可选 red、yellow、blue、green。"));
    }
}

