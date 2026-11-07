// 纯逻辑类：不允许 import org.bukkit。
package cn.infinite.warwall.core;

import java.util.Locale;

/**
 * 死斗阶段内部的压迫阶段，对应数据包 {@code GAME_PRO} 6~11。
 *
 * <p>数据包在 {@code system/timer/main.mcfunction:12-18} 里按 {@code GAME_PRO} 递增，
 * 逐步给全场加饥饿 / 中毒 / 凋零，最后投放「伊甸之果」。这里把它抽成显式枚举，
 * 避免用一个魔法数字表达状态。
 */
public enum PressureStage {

    /** 死斗刚开始，没有额外压迫。数据包 GAME_PRO 5。 */
    NONE("死斗", 180),
    /** 团队增益刷新。数据包 GAME_PRO 6，2400 tick。 */
    TEAM_UPGRADE("团队增益", 120),
    /** 全体中毒。数据包 GAME_PRO 7，1800 tick。 */
    POISON("全体中毒", 90),
    /** 全体饥饿。数据包 GAME_PRO 8，1800 tick。 */
    HUNGER("全体饥饿", 90),
    /** 全体凋零。数据包 GAME_PRO 9，1800 tick。 */
    WITHER("全体凋零", 90),
    /** 伊甸之果降临场地中央。数据包 GAME_PRO 10，3600 tick。 */
    EDEN_APPLE("伊甸之果", 180),
    /** 最终死斗，无时限，直到只剩一支队伍。数据包 GAME_PRO 11，99999 tick。 */
    LAST_STAND("最终死斗", -1);

    private final String chineseName;
    private final int defaultSeconds;

    PressureStage(String chineseName, int defaultSeconds) {
        this.chineseName = chineseName;
        this.defaultSeconds = defaultSeconds;
    }

    public String chineseName() {
        return chineseName;
    }

    public int defaultSeconds() {
        return defaultSeconds;
    }

    /** 下一阶段；{@link #LAST_STAND} 不再推进。 */
    public PressureStage next() {
        return switch (this) {
            case NONE -> TEAM_UPGRADE;
            case TEAM_UPGRADE -> POISON;
            case POISON -> HUNGER;
            case HUNGER -> WITHER;
            case WITHER -> EDEN_APPLE;
            case EDEN_APPLE -> LAST_STAND;
            case LAST_STAND -> LAST_STAND;
        };
    }

    public static PressureStage parse(String raw) {
        if (raw == null) {
            return NONE;
        }
        String key = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (PressureStage stage : values()) {
            if (stage.name().equals(key)) {
                return stage;
            }
        }
        return NONE;
    }
}
