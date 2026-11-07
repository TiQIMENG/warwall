package cn.infinite.warwall.world.room;

import java.util.Properties;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Objects;

public record RoomSettings(int preparationMinutes, int battleMinutes, int firstShrinkMinutes,
                           int secondShrinkMinutes, boolean events, boolean urf,
                           boolean friendlyFire, boolean requireReady, boolean publicRoom,
                           WarwallMode mode, int teamCount, CombatTeam bossTeam,
                           Set<NativeEvent> disabledEvents, Map<Integer, Integer> lateSeconds) {
    public RoomSettings {
        range(preparationMinutes, 1, 60, "发育时长");
        range(battleMinutes, 1, 60, "战斗时长");
        // 原数据包边界收缩分别需要 3 分钟和 2 分钟，不能提前进入下一阶段。
        range(firstShrinkMinutes, 3, 60, "第一次缩圈阶段");
        range(secondShrinkMinutes, 2, 60, "第二次缩圈阶段");
        Objects.requireNonNull(mode); Objects.requireNonNull(bossTeam);
        if (teamCount < 1 || teamCount > 4) throw new IllegalArgumentException("队伍数量应在 1～4 队之间。");
        if (mode == WarwallMode.BOSS && teamCount != 2) throw new IllegalArgumentException("Boss 模式使用两队。");
        if (!activeTeams(teamCount).contains(bossTeam)) throw new IllegalArgumentException("Boss 队伍必须是已启用的队伍。");
        disabledEvents = Set.copyOf(disabledEvents); lateSeconds = Map.copyOf(lateSeconds);
        if (!lateSeconds.keySet().equals(LatePhase.defaults().keySet())) throw new IllegalArgumentException("后期计时设置不完整。");
        lateSeconds.forEach((phase, seconds) -> {
            if (seconds < 15 || seconds > 7200) throw new IllegalArgumentException(LatePhase.of(phase).display() + "应在 15～7200 秒之间。");
        });
    }
    /** 兼容升级前的配置与预设，新增选项采用原生默认值。 */
    public RoomSettings(int preparation, int battle, int first, int second, boolean events,
                        boolean urf, boolean friendly, boolean ready, boolean publicRoom) {
        this(preparation, battle, first, second, events, urf, friendly, ready, publicRoom,
                WarwallMode.NORMAL, 4, CombatTeam.RED, Set.of(), LatePhase.defaults());
    }
    private static void range(int value, int min, int max, String name) {
        if (value < min || value > max) throw new IllegalArgumentException(name + "应在 " + min + "～" + max + " 分钟之间。");
    }
    public static RoomSettings defaults() { return new RoomSettings(40, 20, 10, 10, false, false, false, false, true); }
    public static List<CombatTeam> activeTeams(int count) {
        if (count < 1 || count > 4) throw new IllegalArgumentException("队伍数量应在 1～4 队之间。");
        // 原生队长分队按 tid 从队伍数递减到 1；一队入口单独使用红队。
        return switch (count) { case 1 -> List.of(CombatTeam.RED); case 2 -> List.of(CombatTeam.BLUE, CombatTeam.GREEN);
            case 3 -> List.of(CombatTeam.BLUE, CombatTeam.GREEN, CombatTeam.YELLOW); default -> List.of(CombatTeam.values()); };
    }
    public List<CombatTeam> activeTeams() { return activeTeams(teamCount); }
    public String modeSummary() { return mode.display() + (urf ? " + 无限火力" : ""); }
    public boolean eventEnabled(NativeEvent event) { return !disabledEvents.contains(event); }
    public RoomSettings withMode(WarwallMode value) {
        return copy(value, value == WarwallMode.BOSS ? 2 : teamCount, urf,
                value == WarwallMode.BOSS ? activeTeams(2).getFirst() : bossTeam, disabledEvents, lateSeconds);
    }
    public RoomSettings withTeams(int count) {
        return copy(mode, count, urf, activeTeams(count).contains(bossTeam) ? bossTeam : activeTeams(count).getFirst(), disabledEvents, lateSeconds);
    }
    public RoomSettings withBossTeam(CombatTeam team) { return copy(mode, teamCount, urf, team, disabledEvents, lateSeconds); }
    public RoomSettings toggleEvent(NativeEvent event) {
        Set<NativeEvent> values = EnumSet.noneOf(NativeEvent.class); values.addAll(disabledEvents);
        if (!values.add(event)) values.remove(event);
        return copy(mode, teamCount, urf, bossTeam, values, lateSeconds);
    }
    public RoomSettings allEvents(boolean enabled) {
        return copy(mode, teamCount, urf, bossTeam, enabled ? Set.of() : EnumSet.allOf(NativeEvent.class), lateSeconds);
    }
    private RoomSettings copy(WarwallMode mode, int count, boolean urf, CombatTeam boss, Set<NativeEvent> excluded, Map<Integer, Integer> timings) {
        return new RoomSettings(preparationMinutes, battleMinutes, firstShrinkMinutes, secondShrinkMinutes,
                events, urf, friendlyFire, requireReady, publicRoom, mode, count, boss, excluded, timings);
    }
    private RoomSettings keep(RoomSettings value) {
        return new RoomSettings(value.preparationMinutes, value.battleMinutes, value.firstShrinkMinutes, value.secondShrinkMinutes,
                value.events, value.urf, value.friendlyFire, value.requireReady, value.publicRoom,
                mode, teamCount, bossTeam, disabledEvents, lateSeconds);
    }
    public int numeric(String key) {
        if (key.startsWith("phase-")) return lateSeconds.get(LatePhase.of(Integer.parseInt(key.substring(6))).phase());
        return switch (key) { case "preparation" -> preparationMinutes; case "battle" -> battleMinutes;
            case "first-shrink" -> firstShrinkMinutes; case "second-shrink" -> secondShrinkMinutes;
            default -> throw new IllegalArgumentException("未知时长设置。"); };
    }
    public RoomSettings adjust(String key, int delta) {
        if (key.startsWith("phase-")) {
            int number = LatePhase.of(Integer.parseInt(key.substring(6))).phase();
            Map<Integer, Integer> values = new HashMap<>(lateSeconds); values.put(number, Math.addExact(values.get(number), delta));
            return copy(mode, teamCount, urf, bossTeam, disabledEvents, values);
        }
        return keep(switch (key) {
            case "preparation" -> new RoomSettings(preparationMinutes + delta, battleMinutes, firstShrinkMinutes, secondShrinkMinutes, events, urf, friendlyFire, requireReady, publicRoom);
            case "battle" -> new RoomSettings(preparationMinutes, battleMinutes + delta, firstShrinkMinutes, secondShrinkMinutes, events, urf, friendlyFire, requireReady, publicRoom);
            case "first-shrink" -> new RoomSettings(preparationMinutes, battleMinutes, firstShrinkMinutes + delta, secondShrinkMinutes, events, urf, friendlyFire, requireReady, publicRoom);
            case "second-shrink" -> new RoomSettings(preparationMinutes, battleMinutes, firstShrinkMinutes, secondShrinkMinutes + delta, events, urf, friendlyFire, requireReady, publicRoom);
            default -> throw new IllegalArgumentException("未知时长设置。");
        });
    }
    public RoomSettings toggle(String key) {
        if (!java.util.Set.of("events", "urf", "friendly-fire", "require-ready", "public-room").contains(key)) {
            throw new IllegalArgumentException("未知开关设置。");
        }
        return keep(new RoomSettings(preparationMinutes, battleMinutes, firstShrinkMinutes, secondShrinkMinutes,
                key.equals("events") ? !events : events, key.equals("urf") ? !urf : urf,
                key.equals("friendly-fire") ? !friendlyFire : friendlyFire,
                key.equals("require-ready") ? !requireReady : requireReady,
                key.equals("public-room") ? !publicRoom : publicRoom));
    }
    public int ticksForPhase(int phase) {
        return switch (phase) {
            case 1 -> preparationMinutes * 1200; case 2 -> battleMinutes * 1200;
            case 3 -> firstShrinkMinutes * 1200; case 4 -> secondShrinkMinutes * 1200;
            default -> lateSeconds.containsKey(phase) ? lateSeconds.get(phase) * 20 : -1;
        };
    }
    public Properties toProperties() {
        Properties values = new Properties();
        values.setProperty("preparation", String.valueOf(preparationMinutes));
        values.setProperty("battle", String.valueOf(battleMinutes));
        values.setProperty("first-shrink", String.valueOf(firstShrinkMinutes));
        values.setProperty("second-shrink", String.valueOf(secondShrinkMinutes));
        values.setProperty("events", String.valueOf(events)); values.setProperty("urf", String.valueOf(urf));
        values.setProperty("friendly-fire", String.valueOf(friendlyFire));
        values.setProperty("require-ready", String.valueOf(requireReady));
        values.setProperty("public-room", String.valueOf(publicRoom));
        values.setProperty("schema", "2"); values.setProperty("mode", mode.name());
        values.setProperty("teams", String.valueOf(teamCount)); values.setProperty("boss-team", bossTeam.key());
        for (NativeEvent event : NativeEvent.values()) values.setProperty("event." + event.key(), String.valueOf(eventEnabled(event)));
        for (LatePhase phase : LatePhase.values()) values.setProperty(phase.key(), String.valueOf(lateSeconds.get(phase.phase())));
        return values;
    }
    public static RoomSettings fromProperties(Properties values) {
        String schema = values.getProperty("schema", "1");
        if (!schema.equals("1") && !schema.equals("2")) throw new IllegalArgumentException("不支持这个预设版本。");
        Set<NativeEvent> disabled = EnumSet.noneOf(NativeEvent.class);
        for (NativeEvent event : NativeEvent.values()) if (!bool(values, "event." + event.key(), "true")) disabled.add(event);
        Map<Integer, Integer> timings = new HashMap<>();
        for (LatePhase phase : LatePhase.values()) timings.put(phase.phase(), Integer.parseInt(values.getProperty(phase.key(), String.valueOf(phase.seconds()))));
        WarwallMode mode = WarwallMode.parse(values.getProperty("mode", "normal"));
        int count = Integer.parseInt(values.getProperty("teams", mode == WarwallMode.BOSS ? "2" : "4"));
        String fallbackBoss = activeTeams(count).getFirst().key();
        return new RoomSettings(integer(values, "preparation"), integer(values, "battle"),
                integer(values, "first-shrink"), integer(values, "second-shrink"),
                bool(values, "events"), bool(values, "urf"), bool(values, "friendly-fire"),
                bool(values, "require-ready"), bool(values, "public-room"),
                mode, count, CombatTeam.parse(values.getProperty("boss-team", fallbackBoss)), disabled, timings);
    }
    private static int integer(Properties values, String key) { return Integer.parseInt(values.getProperty(key, "")); }
    private static boolean bool(Properties values, String key) {
        return bool(values, key, "");
    }
    private static boolean bool(Properties values, String key, String fallback) {
        String value = values.getProperty(key, fallback);
        if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException("预设中的开关值无效：" + key);
        return Boolean.parseBoolean(value);
    }
}

