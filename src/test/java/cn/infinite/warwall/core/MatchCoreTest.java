package cn.infinite.warwall.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 纯逻辑核心类的单元测试：阶段枚举、阶段规则矩阵、时长快照、压迫阶段。
 *
 * <p>这些类刻意不 import 任何 org.bukkit 类型，所以可以脱离服务端直接跑。
 */
class MatchCoreTest {

    // ===== MatchState =====

    @Test
    @DisplayName("阶段线性推进：PREP 之后必须是 BATTLE，FINAL_FIGHT 之后是 ENDED")
    void stateProgression() {
        assertEquals(MatchState.COUNTDOWN, MatchState.WAITING.next());
        assertEquals(MatchState.PREP, MatchState.COUNTDOWN.next());
        assertEquals(MatchState.BATTLE, MatchState.PREP.next());
        assertEquals(MatchState.SHRINK_1, MatchState.BATTLE.next());
        assertEquals(MatchState.SHRINK_2, MatchState.SHRINK_1.next());
        assertEquals(MatchState.FINAL_FIGHT, MatchState.SHRINK_2.next());
        assertEquals(MatchState.ENDED, MatchState.FINAL_FIGHT.next());
        assertEquals(MatchState.WAITING, MatchState.ENDED.next());
    }

    @Test
    @DisplayName("解析宽松：忽略大小写与连字符，未知值退回 WAITING")
    void stateParse() {
        assertEquals(MatchState.FINAL_FIGHT, MatchState.parse("final-fight"));
        assertEquals(MatchState.SHRINK_1, MatchState.parse("shrink_1"));
        assertEquals(MatchState.BATTLE, MatchState.parse("  Battle  "));
        assertEquals(MatchState.WAITING, MatchState.parse("WALL_DROP"));
        assertEquals(MatchState.WAITING, MatchState.parse(null));
    }

    @Test
    @DisplayName("只有 BATTLE/缩圈/死斗算战斗阶段，且都属于进行中")
    void stateCombatFlags() {
        assertFalse(MatchState.PREP.isCombat());
        assertTrue(MatchState.BATTLE.isCombat());
        assertTrue(MatchState.SHRINK_1.isCombat());
        assertTrue(MatchState.SHRINK_2.isCombat());
        assertTrue(MatchState.FINAL_FIGHT.isCombat());
        assertFalse(MatchState.ENDED.isCombat());
        assertFalse(MatchState.WAITING.isRunning());
        assertTrue(MatchState.PREP.isRunning());
    }

    // ===== PhaseRules =====

    @Test
    @DisplayName("准备期：墙在、凋零在场但打不动、核心打不动、不掉落、可编辑方块")
    void prepRules() {
        PhaseRules rules = PhaseRules.rulesFor(MatchState.PREP);
        assertFalse(rules.pvpAllowed(), "准备期不能打架");
        assertTrue(rules.wallSolid(), "准备期墙必须站着");
        assertTrue(rules.witherPresent(), "准备期凋零已生成");
        assertFalse(rules.witherAttackable(), "准备期凋零无敌");
        assertFalse(rules.nexusAttackable(), "准备期核心无敌");
        assertFalse(rules.dropItemsOnDeath(), "准备期死亡不掉落");
        assertTrue(rules.blocksEditable(), "准备期要能挖矿");
        assertEquals(RespawnPolicy.ALWAYS, rules.respawn());
    }

    @Test
    @DisplayName("战斗期：可打、墙没了、全掉落、复活看凋零")
    void battleRules() {
        PhaseRules rules = PhaseRules.rulesFor(MatchState.BATTLE);
        assertTrue(rules.pvpAllowed());
        assertFalse(rules.wallSolid());
        assertTrue(rules.witherAttackable());
        assertTrue(rules.nexusAttackable());
        assertTrue(rules.dropItemsOnDeath());
        assertEquals(RespawnPolicy.WHILE_OWN_WITHER_ALIVE, rules.respawn());
    }

    @Test
    @DisplayName("死斗期：凋零已灭、禁止复活")
    void finalFightRules() {
        PhaseRules rules = PhaseRules.rulesFor(MatchState.FINAL_FIGHT);
        assertTrue(rules.pvpAllowed());
        assertFalse(rules.witherPresent());
        assertFalse(rules.witherAttackable());
        assertEquals(RespawnPolicy.NEVER, rules.respawn());
    }

    @Test
    @DisplayName("友伤永远关闭")
    void friendlyFireAlwaysOff() {
        for (MatchState state : MatchState.values()) {
            assertFalse(PhaseRules.rulesFor(state).friendlyFire(),
                    "阶段 " + state + " 不允许友伤");
        }
    }

    // ===== MatchSettings =====

    @Test
    @DisplayName("默认值是战墙节奏：5 分钟准备 + 10 分钟战斗")
    void defaultSettings() {
        MatchSettings settings = MatchSettings.defaults();
        assertEquals(300, settings.prepSeconds());
        assertEquals(600, settings.battleSeconds());
        assertEquals(300, settings.shrink1Seconds());
        assertEquals(300, settings.shrink2Seconds());
        assertEquals(180, settings.finalFightSeconds());
    }

    @Test
    @DisplayName("wallwar 预设还原数据包原始长度：2400/1200/600/600")
    void wallwarPreset() {
        MatchSettings preset = MatchSettings.defaults().wallwarPreset();
        assertEquals(2400, preset.prepSeconds());
        assertEquals(1200, preset.battleSeconds());
        assertEquals(600, preset.shrink1Seconds());
        assertEquals(600, preset.shrink2Seconds());
    }

    @Test
    @DisplayName("快速模式把所有阶段压到 10 秒")
    void fastMode() {
        MatchSettings fast = MatchSettings.defaults().fastMode();
        for (MatchState state : MatchState.values()) {
            if (!state.isRunning()) {
                continue;
            }
            assertEquals(10, fast.secondsOf(state), "阶段 " + state + " 应该是 10 秒");
        }
    }

    @Test
    @DisplayName("无限阶段返回 -1，有限阶段 tick 数是秒数乘 20")
    void ticksConversion() {
        MatchSettings settings = MatchSettings.defaults();
        assertEquals(-1, settings.secondsOf(MatchState.WAITING));
        assertEquals(-1L, settings.ticksOf(MatchState.WAITING));
        assertEquals(300L * 20L, settings.ticksOf(MatchState.PREP));
    }

    @Test
    @DisplayName("warnAtSeconds 返回副本，外部修改不影响内部")
    void warnArrayDefensiveCopy() {
        MatchSettings settings = new MatchSettings(1, 2, 3, 4, 5, 6, 7, new int[] {10, 5});
        int[] got = settings.warnAtSeconds();
        got[0] = 999;
        assertEquals(10, settings.warnAtSeconds()[0]);
        assertArrayEquals(new int[] {10, 5}, settings.warnAtSeconds());
    }

    // ===== PressureStage =====

    @Test
    @DisplayName("压迫阶段按数据包顺序推进，LAST_STAND 自我循环")
    void pressureChain() {
        assertEquals(PressureStage.TEAM_UPGRADE, PressureStage.NONE.next());
        assertEquals(PressureStage.POISON, PressureStage.TEAM_UPGRADE.next());
        assertEquals(PressureStage.HUNGER, PressureStage.POISON.next());
        assertEquals(PressureStage.WITHER, PressureStage.HUNGER.next());
        assertEquals(PressureStage.EDEN_APPLE, PressureStage.WITHER.next());
        assertEquals(PressureStage.LAST_STAND, PressureStage.EDEN_APPLE.next());
        assertEquals(PressureStage.LAST_STAND, PressureStage.LAST_STAND.next());
    }

    @Test
    @DisplayName("LAST_STAND 没有时限，其余阶段都有正时长")
    void pressureDurations() {
        assertEquals(-1, PressureStage.LAST_STAND.defaultSeconds());
        for (PressureStage stage : PressureStage.values()) {
            if (stage == PressureStage.LAST_STAND) {
                continue;
            }
            assertTrue(stage.defaultSeconds() > 0, "阶段 " + stage + " 应该有正时长");
        }
    }
}
