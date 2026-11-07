package cn.infinite.warwall.world.room;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

public enum LatePhase {
    DEATH_FIGHT(5, "死斗至团队增益", 180, "gauge4"),
    TEAM_BUFF(6, "团队增益至中毒", 120, "gauge5"),
    POISON(7, "中毒至饥饿", 90, "gauge6"),
    HUNGER(8, "饥饿至凋零", 90, "gauge7"),
    WITHER(9, "凋零至果实刷新", 90, "gauge8"),
    APPLE(10, "果实刷新间隔", 180, "gauge8");
    private final int phase, seconds;
    private final String display, bar;
    LatePhase(int phase, String display, int seconds, String bar) { this.phase = phase; this.display = display; this.seconds = seconds; this.bar = bar; }
    public int phase() { return phase; }
    public int seconds() { return seconds; }
    public String display() { return display; }
    public String bar() { return bar; }
    public String key() { return "phase-" + phase; }
    public static LatePhase of(int number) {
        return Arrays.stream(values()).filter(value -> value.phase == number).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("这个阶段没有可配置的原生计时入口。"));
    }
    public static Map<Integer, Integer> defaults() { return Arrays.stream(values()).collect(Collectors.toUnmodifiableMap(LatePhase::phase, LatePhase::seconds)); }
}

