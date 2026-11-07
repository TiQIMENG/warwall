// 纯逻辑类：不允许 import org.bukkit。
package cn.infinite.warwall.core;

/**
 * 单个阶段的规则快照。所有判断都从这里读，避免在监听器里散落 if-else。
 *
 * <p>阶段划分依据 wallwar 数据包的 GAMEMODE / GAME_PRO，见 {@link MatchState} 注释。
 *
 * @param pvpAllowed        异队是否可互相造成伤害
 * @param friendlyFire      同队是否可互相伤害（本项目恒为 false）
 * @param witherAttackable  凋零是否可被攻击
 * @param witherPresent     该阶段是否应该存在凋零实体
 * @param nexusAttackable   核心（Nexus）是否可被削血；数据包无核心，此项为可选融合层
 * @param dropItemsOnDeath  死亡是否掉落背包
 * @param respawn           复活策略
 * @param wallSolid         墙是否存在且不可破坏
 * @param blocksEditable    方块是否可破坏/放置
 * @param borderActive      世界边界是否生效
 */
public record PhaseRules(
        boolean pvpAllowed,
        boolean friendlyFire,
        boolean witherAttackable,
        boolean witherPresent,
        boolean nexusAttackable,
        boolean dropItemsOnDeath,
        RespawnPolicy respawn,
        boolean wallSolid,
        boolean blocksEditable,
        boolean borderActive) {

    public static PhaseRules rulesFor(MatchState state) {
        return switch (state) {
            // 空闲与倒计时：不打架、不掉落。
            case WAITING -> new PhaseRules(false, false, false, false, false, false,
                    RespawnPolicy.ALWAYS, false, false, false);
            case COUNTDOWN -> new PhaseRules(false, false, false, false, false, false,
                    RespawnPolicy.ALWAYS, true, false, false);
            // 准备期：墙在、凋零无敌、可挖矿但不能打架（数据包 GAMEMODE 1）。
            case PREP -> new PhaseRules(false, false, false, true, false, false,
                    RespawnPolicy.ALWAYS, true, true, false);
            // 战斗期：墙倒、凋零可打、死亡掉落（数据包 battle_start：keep_inventory false）。
            case BATTLE -> new PhaseRules(true, false, true, true, true, true,
                    RespawnPolicy.WHILE_OWN_WITHER_ALIVE, false, true, true);
            // 两次缩圈仍是战斗，规则不变，只是边界在动。
            case SHRINK_1 -> new PhaseRules(true, false, true, true, true, true,
                    RespawnPolicy.WHILE_OWN_WITHER_ALIVE, false, true, true);
            case SHRINK_2 -> new PhaseRules(true, false, true, true, true, true,
                    RespawnPolicy.WHILE_OWN_WITHER_ALIVE, false, true, true);
            // 死斗：数据包 death_fight 把边界伤害拉到 20，且复活已无意义。
            case FINAL_FIGHT -> new PhaseRules(true, false, false, false, true, true,
                    RespawnPolicy.NEVER, false, true, true);
            case ENDED -> new PhaseRules(false, false, false, false, false, false,
                    RespawnPolicy.NEVER, false, false, false);
        };
    }
}
