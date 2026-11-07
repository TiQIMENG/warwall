// 纯逻辑类：不允许 import org.bukkit。
package cn.infinite.warwall.core;

/** 死亡后的复活策略。 */
public enum RespawnPolicy {

    /** 总是可以复活（等待期、准备期）。 */
    ALWAYS,

    /** 只有己方凋零还活着才能复活（战斗期）。 */
    WHILE_OWN_WITHER_ALIVE,

    /** 不再复活，死亡即转为旁观（死斗期与结算期）。 */
    NEVER
}
