// 纯逻辑类：不允许 import org.bukkit。
package cn.infinite.warwall.core;

/**
 * 一局的时长快照（秒）。默认值直接取自 wallwar 数据包的 tick 数除以 20。
 *
 * <p>数据包原始值：准备 48000t、战斗 24000t、缩圈各 12000t、死斗 3600t。
 *
 * @param countdownSeconds 开赛倒计时
 * @param prepSeconds      资源收集期（天空之壁站立）
 * @param battleSeconds    战斗期
 * @param shrink1Seconds   第一次缩圈
 * @param shrink2Seconds   第二次缩圈
 * @param finalFightSeconds 死斗（压迫阶段 NONE 的时长）
 * @param endedSeconds     结算展示
 * @param warnAtSeconds    准备期剩余秒数播报点（降序）
 */
public record MatchSettings(
        int countdownSeconds,
        int prepSeconds,
        int battleSeconds,
        int shrink1Seconds,
        int shrink2Seconds,
        int finalFightSeconds,
        int endedSeconds,
        int[] warnAtSeconds) {

    public MatchSettings {
        warnAtSeconds = warnAtSeconds == null ? new int[0] : warnAtSeconds.clone();
    }

    /**
     * 超级战墙的经典节奏：5 分钟准备 + 10 分钟战斗，之后接 wallwar 数据包的两次缩圈与死斗。
     *
     * <p>要还原数据包原始长度（准备 48000t / 战斗 24000t / 缩圈各 12000t），
     * 在 config.yml 把 prep 改 2400、battle 改 1200、两次缩圈各改 600，再用 {@link #wallwarPreset()}。
     */
    public static MatchSettings defaults() {
        return new MatchSettings(30, 300, 600, 300, 300, 180, 20,
                new int[] {300, 180, 120, 60, 30, 10});
    }

    /** ShiinaYumeka/wallwar 数据包的原始长度。 */
    public MatchSettings wallwarPreset() {
        return new MatchSettings(30, 2400, 1200, 600, 600, 180, 20,
                new int[] {1800, 1200, 600, 300, 180, 120, 60, 30, 10});
    }

    /** 快节奏：把每个阶段压到 10 秒，用于调试与冒烟测试。 */
    public MatchSettings fastMode() {
        return new MatchSettings(10, 10, 10, 10, 10, 10, 10, new int[] {5});
    }

    /** 标准公开服节奏：准备 10 分钟、战斗 10 分钟、两次缩圈各 5 分钟。 */
    public MatchSettings standardPreset() {
        return new MatchSettings(30, 600, 600, 300, 300, 180, 20,
                new int[] {300, 180, 120, 60, 30, 10});
    }

    /** 该阶段的时长（秒）；WAITING 与 LAST_STAND 返回 -1。 */
    public int secondsOf(MatchState state) {
        return switch (state) {
            case WAITING -> -1;
            case COUNTDOWN -> countdownSeconds;
            case PREP -> prepSeconds;
            case BATTLE -> battleSeconds;
            case SHRINK_1 -> shrink1Seconds;
            case SHRINK_2 -> shrink2Seconds;
            case FINAL_FIGHT -> finalFightSeconds;
            case ENDED -> endedSeconds;
        };
    }

    /** 该阶段的时长（tick）；无限阶段返回 -1。 */
    public long ticksOf(MatchState state) {
        int seconds = secondsOf(state);
        return seconds < 0 ? -1L : seconds * 20L;
    }

    public int secondsOf(PressureStage stage) {
        return stage.defaultSeconds();
    }

    @Override
    public int[] warnAtSeconds() {
        return warnAtSeconds.clone();
    }
}
