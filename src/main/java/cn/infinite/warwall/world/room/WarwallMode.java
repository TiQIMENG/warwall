package cn.infinite.warwall.world.room;

import java.util.Locale;

public enum WarwallMode {
    NORMAL("常规战墙"), BOSS("Boss 战墙");
    private final String display;
    WarwallMode(String display) { this.display = display; }
    public String display() { return display; }
    public static WarwallMode parse(String value) {
        try { return valueOf(value.toUpperCase(Locale.ROOT)); }
        catch (RuntimeException exception) { throw new IllegalArgumentException("模式可选 normal 或 boss。"); }
    }
}

