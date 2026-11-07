package cn.infinite.warwall.config;

import cn.infinite.warwall.core.MatchSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * config.yml 的类型化访问层。
 *
 * <p>所有读取都带默认值，配置文件缺键不会让插件起不来；写回只在 {@code /warwall reload}
 * 之后由 {@link #reload()} 重新构建，运行期不重复读盘。
 */
public final class WarwallConfig {

    private final JavaPlugin plugin;
    private FileConfiguration raw;

    private MatchSettings settings = MatchSettings.defaults();
    private boolean debug;
    private String prefix;

    public WarwallConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    /** 重新读盘并重建所有快照。 */
    public void reload() {
        plugin.reloadConfig();
        raw = plugin.getConfig();
        // 默认值按「超级战墙」节奏：5 分钟准备 + 10 分钟战斗，之后接两次缩圈与死斗压迫。
        // 想还原 wallwar 数据包原始长度，把 prep 改 2400、battle 改 1200、两次缩圈各 600。
        settings = new MatchSettings(
                intAt("phases.countdown-seconds", 30),
                intAt("phases.prep-seconds", 300),
                intAt("phases.battle-seconds", 600),
                intAt("phases.shrink-1-seconds", 300),
                intAt("phases.shrink-2-seconds", 300),
                intAt("phases.final-fight-seconds", 180),
                intAt("phases.ended-seconds", 20),
                intList("phases.warn-at-seconds", new int[] {300, 180, 120, 60, 30, 10}));
        debug = raw.getBoolean("debug", false);
        prefix = colorize(raw.getString("prefix", "&8[&c战墙&8] &r"));
    }

    // ===== 基础 =====

    public FileConfiguration raw() {
        return raw;
    }

    public MatchSettings settings() {
        return settings;
    }

    public boolean debug() {
        return debug;
    }

    public String prefix() {
        return prefix;
    }

    /** 把 & 颜色码转成 §；配置里统一写 &。 */
    public static String colorize(String text) {
        if (text == null) {
            return "";
        }
        char[] chars = text.toCharArray();
        for (int i = 0; i < chars.length - 1; i++) {
            if (chars[i] == '&' && "0123456789AaBbCcDdEeFfKkLlMmNnOoRrXx".indexOf(chars[i + 1]) >= 0) {
                chars[i] = '\u00A7';
            }
        }
        return new String(chars);
    }

    // ===== 玩家 =====

    public double playerMaxHealth() {
        return clamp(raw.getDouble("players.max-health", 40.0), 2.0, 40.0);
    }

    public double healOnKill() {
        return clamp(raw.getDouble("players.heal-on-kill", 4.0), 0.0, 20.0);
    }

    public int respawnDelaySeconds() {
        return intAt("players.respawn-delay-seconds", 5);
    }

    public int respawnInvulnerableSeconds() {
        return intAt("players.respawn-invulnerable-seconds", 3);
    }

    public boolean keepInventoryInPrep() {
        return raw.getBoolean("players.keep-inventory-in-prep", true);
    }

    public boolean dropHeadOnDeath() {
        return raw.getBoolean("players.drop-head-on-death", true);
    }

    public boolean dropGoldenAppleOnDeath() {
        return raw.getBoolean("players.drop-golden-apple-on-death", true);
    }

    public boolean spectatorAfterWitherDeath() {
        return raw.getBoolean("players.spectator-after-wither-death", true);
    }

    public boolean clearKitOnSpectate() {
        return raw.getBoolean("players.clear-kit-on-spectate", true);
    }

    // ===== 战斗 =====

    public boolean naturalRegeneration() {
        return raw.getBoolean("combat.natural-regeneration", false);
    }

    public boolean friendlyFire() {
        return raw.getBoolean("combat.friendly-fire", false);
    }

    public double meleeDamageMultiplier() {
        return clamp(raw.getDouble("combat.melee-damage-multiplier", 1.0), 0.1, 5.0);
    }

    public double projectileDamageMultiplier() {
        return clamp(raw.getDouble("combat.projectile-damage-multiplier", 1.0), 0.1, 5.0);
    }

    public double trueDamageMultiplier() {
        return clamp(raw.getDouble("combat.true-damage-multiplier", 1.0), 0.1, 5.0);
    }

    public double attackKnockback() {
        return clamp(raw.getDouble("combat.attack-knockback", 1.0), 0.0, 4.0);
    }

    public int maxNoDamageTicks() {
        return intAt("combat.max-no-damage-ticks", 20);
    }

    public boolean resetCooldownOnSwap() {
        return raw.getBoolean("combat.reset-cooldown-on-swap", true);
    }

    public int assistWindowSeconds() {
        return intAt("combat.assist-window-seconds", 10);
    }

    public double assistMinDamage() {
        return raw.getDouble("combat.assist-min-damage", 6.0);
    }

    public int gappleCooldownSeconds() {
        return intAt("combat.gapple-cooldown-seconds", 30);
    }

    public List<PotionEffectType> potionBlacklist() {
        List<PotionEffectType> result = new ArrayList<>();
        for (String name : raw.getStringList("combat.potion-blacklist")) {
            PotionEffectType type = PotionEffectType.getByName(name.trim().toUpperCase(Locale.ROOT));
            if (type != null) {
                result.add(type);
            } else if (debug) {
                plugin.getLogger().warning("combat.potion-blacklist 里的 " + name + " 不是有效药水效果，已忽略");
            }
        }
        return result;
    }

    public List<Material> itemBlacklist() {
        List<Material> result = new ArrayList<>();
        for (String name : raw.getStringList("combat.item-blacklist")) {
            Material material = Material.matchMaterial(name.trim().toUpperCase(Locale.ROOT));
            if (material != null) {
                result.add(material);
            } else if (debug) {
                plugin.getLogger().warning("combat.item-blacklist 里的 " + name + " 不是有效材质，已忽略");
            }
        }
        return result;
    }

    // ===== 凋零 =====

    public double witherMaxHealth() {
        return clamp(raw.getDouble("wither.max-health", 1000.0), 100.0, 4000.0);
    }

    public int witherInvulnerableSeconds() {
        return intAt("wither.invulnerable-seconds", 5);
    }

    public int witherEnragePercent() {
        return intAt("wither.enrage-health-percent", 50);
    }

    public int witherSkullIntervalSeconds() {
        return intAt("wither.skull-interval-seconds", 4);
    }

    public int witherEnrageSkullIntervalSeconds() {
        return intAt("wither.enrage-skull-interval-seconds", 2);
    }

    public int witherSkillIntervalSeconds() {
        return intAt("wither.skill-interval-seconds", 7);
    }

    public double witherSkillRadius() {
        return raw.getDouble("wither.skill-radius", 20.0);
    }

    public double witherAggroRadius() {
        return raw.getDouble("wither.aggro-radius", 40.0);
    }

    public double damageToWitherMultiplier() {
        return clamp(raw.getDouble("wither.damage-to-wither-multiplier", 1.0), 0.1, 5.0);
    }

    // ===== 边界 =====

    public double borderBaseRadius() {
        return raw.getDouble("border.base-radius", 300.0);
    }

    public double borderDamagePerSecond() {
        return raw.getDouble("border.damage-per-second", 0.5);
    }

    public int borderWarningDistance() {
        return intAt("border.warning-distance", 5);
    }

    public double deathmatchRadius() {
        return raw.getDouble("border.deathmatch.radius", 60.0);
    }

    public int deathmatchShrinkSeconds() {
        return intAt("border.deathmatch.duration-seconds", 60);
    }

    public double deathmatchDamagePerSecond() {
        return raw.getDouble("border.deathmatch.damage-per-second", 4.0);
    }

    // ===== 核心 Nexus（融合「核心战争」玩法）=====

    public boolean nexusEnabled() {
        return raw.getBoolean("nexus.enabled", true);
    }

    public int nexusMaxHealth() {
        return intAt("nexus.max-health", 75);
    }

    public int nexusBlocksPlacedDamage() {
        return intAt("nexus.blocks-placed-damage", 1);
    }

    public int nexusBlocksBrokenDamage() {
        return intAt("nexus.blocks-broken-damage", 1);
    }

    public String nexusTeamBuffName() {
        return raw.getString("nexus.team-buff", "FAST_DIGGING");
    }

    public int nexusTeamBuffAmplifier() {
        return intAt("nexus.team-buff-amplifier", 0);
    }

    public int nexusTeamBuffRefreshTicks() {
        return intAt("nexus.team-buff-refresh-ticks", 100);
    }

    public double nexusRespawnScatterRadius() {
        return raw.getDouble("nexus.respawn-scatter-radius", 8.0);
    }

    public int nexusRespawnArmorLoss() {
        return intAt("nexus.respawn-armor-loss", 1);
    }

    // ===== 职业与发育 =====

    /**
     * 开局物资。本插件只有**一条**发育曲线，没有模式开关：
     * 职业只提供「身份标识 + 少量标志性起步物」，铁/钻石装备、附魔、药水全部靠自己挖与炼。
     */
    public java.util.List<Material> starterItems() {
        java.util.List<Material> result = new java.util.ArrayList<>();
        for (String name : raw.getStringList("kits.starter-items")) {
            Material material = Material.matchMaterial(name.trim().toUpperCase(java.util.Locale.ROOT));
            if (material != null) {
                result.add(material);
            }
        }
        return result;
    }

    /** 开局口粮数量。 */
    public int starterFood() {
        return intAt("kits.starter-food", 8);
    }

    /** 开局火把数量。 */
    public int starterTorches() {
        return intAt("kits.starter-torches", 8);
    }

    // ===== 随机地图生成 =====

    /** 开：每局现生成一张新图；关：用 arenas.yml 里手工配的图。 */
    public boolean mapgenEnabled() {
        return raw.getBoolean("mapgen.enabled", true);
    }

    public String mapgenWorld() {
        return raw.getString("mapgen.world", "world");
    }

    public int mapgenCenterX() {
        return intAt("mapgen.center-x", 0);
    }

    public int mapgenCenterY() {
        return intAt("mapgen.center-y", 100);
    }

    public int mapgenCenterZ() {
        return intAt("mapgen.center-z", 0);
    }

    public int mapgenMinRadius() {
        return intAt("mapgen.min-radius", 56);
    }

    public int mapgenMaxRadius() {
        return intAt("mapgen.max-radius", 72);
    }

    public int mapgenMinPlayers() {
        return intAt("mapgen.min-players", 2);
    }

    /** 是否在生成后跑一遍照搬来的领地评分补偿（MapPreparation）。 */
    public boolean mapgenFairnessPass() {
        return raw.getBoolean("mapgen.fairness-pass", true);
    }

    public int mapgenMaxPlayers() {
        return intAt("mapgen.max-players", 100);
    }

    /** 生成失败时的最大重试次数（连通性不合格就换种子）。 */
    public int mapgenMaxAttempts() {
        return intAt("mapgen.max-attempts", 4);
    }

    /** 出生点可达区域占地图总面积的最低比例。 */
    public double mapgenMinLandRatio() {
        return raw.getDouble("mapgen.min-land-ratio", 0.18);
    }

    // ===== 地图池 =====

    /** 多张图之间随机轮换；关掉就固定用 default-arena / setup select 选中的那张。 */
    public boolean arenaRotation() {
        return raw.getBoolean("arenas.rotation", true);
    }

    // ===== 队友发光 =====

    public boolean teamGlowEnabled() {
        return raw.getBoolean("glow.team-glow", true);
    }

    // ===== Boss =====

    public double bossHealth() {
        return clamp(raw.getDouble("boss.health", 200.0), 20.0, 2000.0);
    }

    public double bossDamage() {
        return clamp(raw.getDouble("boss.damage", 8.0), 1.0, 40.0);
    }

    public int bossKillCoins() {
        return intAt("boss.kill-coins", 120);
    }

    public int bossEdenDrops() {
        return intAt("boss.eden-drops", 2);
    }

    // ===== 墙 =====

    public long wallMaxVolume() {
        return raw.getLong("wall.max-volume", 200_000L);
    }

    public int wallRestorePerTick() {
        return intAt("wall.restore-per-tick", 2000);
    }

    // ===== UI / 视觉 / 统计 =====

    public boolean sidebarEnabled() {
        return raw.getBoolean("ui.sidebar", true);
    }

    public boolean actionbarEnergy() {
        return raw.getBoolean("ui.actionbar-energy", true);
    }

    public int sidebarUpdateTicks() {
        return intAt("ui.sidebar-update-ticks", 20);
    }

    public boolean useCustomModelData() {
        return raw.getBoolean("visual.use-custom-model-data", false);
    }

    public int customModelDataBase() {
        return intAt("visual.custom-model-data-base", 1000);
    }

    public boolean hideAttributes() {
        return raw.getBoolean("visual.hide-attributes", true);
    }

    public boolean itemGlintOnAbilityReady() {
        return raw.getBoolean("visual.item-glint-on-ability-ready", true);
    }

    public boolean statsEnabled() {
        return raw.getBoolean("stats.enabled", true);
    }

    public boolean announceMvp() {
        return raw.getBoolean("stats.announce-mvp", true);
    }

    /** 文案读取：返回已上色的字符串；键不存在时返回 fallback。 */
    public String message(String key, String fallback) {
        return colorize(raw.getString("messages." + key, fallback));
    }

    // ===== 工具 =====

    private int intAt(String path, int fallback) {
        return raw.getInt(path, fallback);
    }

    private int[] intList(String path, int[] fallback) {
        List<Integer> list = raw.getIntegerList(path);
        if (list.isEmpty()) {
            return fallback;
        }
        int[] result = new int[list.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = list.get(i);
        }
        return result;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
