package cn.infinite.warwall.world.map;

public enum Resource {
    WOOD("原木", 192), COAL("煤矿", 128), IRON("铁矿", 128), GOLD("金矿", 48), DIAMOND("钻石矿", 24);
    private final String display;
    private final int minimum;
    Resource(String display, int minimum) { this.display = display; this.minimum = minimum; }
    public String display() { return display; }
    public int minimum() { return minimum; }
}

