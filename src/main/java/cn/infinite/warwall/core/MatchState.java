// 纯逻辑类：不允许 import org.bukkit。
package cn.infinite.warwall.core;

import java.util.Locale;

/**
 * 一局比赛的主阶段。
 *
 * <p><b>以 ShiinaYumeka/wallwar 数据包的 GAME_PRO / GAMEMODE 为准</b>：
 * <ul>
 *   <li>数据包 {@code GAMEMODE}：0=空闲 1=准备 2=战斗 3=结束 → 对应 {@link #WAITING}/{@link #PREP}/{@link #BATTLE}/{@link #ENDED}</li>
 *   <li>数据包 {@code GAME_PRO} 3/4 的两次缩圈 → {@link #SHRINK_1}/{@link #SHRINK_2}</li>
 *   <li>数据包 {@code GAME_PRO} 5 的死斗 → {@link #FINAL_FIGHT}，其后的 6~11 由 {@link PressureStage} 承载</li>
 * </ul>
 *
 * <p>顺序：WAITING → COUNTDOWN → PREP → BATTLE → SHRINK_1 → SHRINK_2 → FINAL_FIGHT → ENDED
 */
public enum MatchState {

    /** 等待报名，玩家在大厅。数据包 GAMEMODE 0。 */
    WAITING("等待中", false, false),
    /** 开赛倒计时，玩家已分队并传送进场。 */
    COUNTDOWN("准备开始", false, true),
    /** 准备期：天空之壁还在，凋零无敌，不能打架。数据包 48000 tick = 2400 秒。 */
    PREP("资源收集", false, true),
    /** 战斗期：墙倒，凋零与核心可被攻击。数据包 24000 tick = 1200 秒。 */
    BATTLE("战斗中", true, true),
    /** 第一次缩圈。数据包 circle_1，12000 tick = 600 秒。 */
    SHRINK_1("缩圈·一", true, true),
    /** 第二次缩圈。数据包 circle_2，12000 tick = 600 秒。 */
    SHRINK_2("缩圈·二", true, true),
    /** 死斗：全体传送到死斗平台，边界收到终点，随后进入压迫阶段。数据包 3600 tick = 180 秒。 */
    FINAL_FIGHT("死斗", true, true),
    /** 结算展示。 */
    ENDED("结算", false, false);

    private final String chineseName;
    private final boolean combat;
    private final boolean running;

    MatchState(String chineseName, boolean combat, boolean running) {
        this.chineseName = chineseName;
        this.combat = combat;
        this.running = running;
    }

    public String chineseName() {
        return chineseName;
    }

    /** 该阶段是否允许异队互伤。 */
    public boolean isCombat() {
        return combat;
    }

    /** 该阶段是否属于「一局进行中」（WAITING / ENDED 之外）。 */
    public boolean isRunning() {
        return running;
    }

    /** 是否为缩圈阶段（边界在动）。 */
    public boolean isShrinking() {
        return this == SHRINK_1 || this == SHRINK_2;
    }

    /** 下一个阶段；ENDED 之后回到 WAITING。 */
    public MatchState next() {
        return switch (this) {
            case WAITING -> COUNTDOWN;
            case COUNTDOWN -> PREP;
            case PREP -> BATTLE;
            case BATTLE -> SHRINK_1;
            case SHRINK_1 -> SHRINK_2;
            case SHRINK_2 -> FINAL_FIGHT;
            case FINAL_FIGHT -> ENDED;
            case ENDED -> WAITING;
        };
    }

    /** 宽松解析：大小写不敏感、接受连字符，未知返回 {@link #WAITING}。 */
    public static MatchState parse(String raw) {
        if (raw == null) {
            return WAITING;
        }
        String key = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (MatchState state : values()) {
            if (state.name().equals(key)) {
                return state;
            }
        }
        return WAITING;
    }
}
