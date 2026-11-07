package cn.infinite.warwall.combat;

import cn.infinite.warwall.WarwallPlugin;
import cn.infinite.warwall.core.MatchState;
import cn.infinite.warwall.core.PhaseRules;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.GameRule;
import org.bukkit.World;

/**
 * 世界规则的下发与还原。
 *
 * <p>只在进入/退出阶段时改 gamerule，不在每个事件里改。收局时**一定要还原**，
 * 否则会把同一个世界里其它玩法（或下一局）的规则改坏。
 *
 * <p><b>版本备注</b>：1.21.11 里 {@code NATURAL_REGENERATION}、{@code KEEP_INVENTORY}、
 * {@code DO_IMMEDIATE_RESPAWN}、{@code MOB_GRIEFING}、{@code ANNOUNCE_ADVANCEMENTS}
 * 这五个 {@code GameRule} 常量已标记 {@code forRemoval}，编译会报 removal 警告。
 * 目前 Bukkit 还没有「按字符串改 gamerule」的稳定 API，只能继续用；
 * 升级到正式移除的版本时，这里要改成 {@code world.setGameRuleValue("naturalRegeneration", ...)} 之类的过渡方案。
 */
@SuppressWarnings({"removal", "deprecation"})
public final class CombatManager {

    private final WarwallPlugin plugin;
    private final Map<GameRule<?>, Object> originalValues = new HashMap<>();
    private World appliedWorld;

    public CombatManager(WarwallPlugin plugin) {
        this.plugin = plugin;
    }

    /** 按阶段下发规则。 */
    @SuppressWarnings("unchecked")
    public void applyWorldRules(World world, MatchState state) {
        if (world == null) {
            return;
        }
        if (appliedWorld == null) {
            appliedWorld = world;
            remember(world, GameRule.NATURAL_REGENERATION);
            remember(world, GameRule.KEEP_INVENTORY);
            remember(world, GameRule.DO_IMMEDIATE_RESPAWN);
            remember(world, GameRule.MOB_GRIEFING);
            remember(world, GameRule.ANNOUNCE_ADVANCEMENTS);
        }

        PhaseRules rules = PhaseRules.rulesFor(state);
        world.setGameRule(GameRule.NATURAL_REGENERATION, plugin.config().naturalRegeneration());
        world.setGameRule(GameRule.DO_IMMEDIATE_RESPAWN, false);
        world.setGameRule(GameRule.MOB_GRIEFING, false);
        world.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false);
        // 掉落由 DeathListener 逐次处理，gamerule 保持「不保留背包」，避免和阶段规则打架。
        world.setGameRule(GameRule.KEEP_INVENTORY, false);
    }

    /** 阶段切换时刷新规则。 */
    public void onPhaseChange(MatchState state) {
        if (appliedWorld != null) {
            applyWorldRules(appliedWorld, state);
        }
    }

    /** 还原开局前记录的 gamerule。 */
    public void restoreWorldRules() {
        if (appliedWorld == null) {
            return;
        }
        for (Map.Entry<GameRule<?>, Object> entry : originalValues.entrySet()) {
            setRaw(appliedWorld, entry.getKey(), entry.getValue());
        }
        originalValues.clear();
        appliedWorld = null;
    }

    // ===== 伤害归属与助攻 =====

    /** 受害者 → (伤害来源 → 最后一次伤害时间)。用于助攻窗口判定。 */
    private final Map<java.util.UUID, Map<java.util.UUID, Long>> recentDamagers =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 记一次伤害来源，供死亡时结算助攻。 */
    public void recordDamager(java.util.UUID victim, java.util.UUID attacker) {
        recentDamagers.computeIfAbsent(victim, key -> new java.util.concurrent.ConcurrentHashMap<>())
                .put(attacker, System.currentTimeMillis());
    }

    /** 死亡时结算助攻：窗口内对死者造成过伤害的其他人各记一次。 */
    public void awardAssists(org.bukkit.entity.Player victim) {
        Map<java.util.UUID, Long> damagers = recentDamagers.remove(victim.getUniqueId());
        if (damagers == null) {
            return;
        }
        long window = plugin.config().assistWindowSeconds() * 1000L;
        long now = System.currentTimeMillis();
        for (Map.Entry<java.util.UUID, Long> entry : damagers.entrySet()) {
            if (now - entry.getValue() > window) {
                continue;
            }
            org.bukkit.entity.Player helper = org.bukkit.Bukkit.getPlayer(entry.getKey());
            if (helper != null && !helper.equals(victim)) {
                plugin.stats().onAssist(helper);
            }
        }
    }

    /** 玩家退出/重开时清理，避免长时间运行后残留。 */
    public void forget(java.util.UUID uuid) {
        recentDamagers.remove(uuid);
    }

    @SuppressWarnings("unchecked")
    private <T> void remember(World world, GameRule<T> rule) {
        T value = world.getGameRuleValue(rule);
        if (value != null) {
            originalValues.put(rule, value);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> void setRaw(World world, GameRule<?> rule, Object value) {
        world.setGameRule((GameRule<T>) rule, (T) value);
    }
}
