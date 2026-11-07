package cn.infinite.warwall.item;

import cn.infinite.warwall.WarwallPlugin;
import cn.infinite.warwall.team.TeamColor;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * 自定义物品体系。
 *
 * <p>从 wallwar 数据包的 {@code weapon/*}、{@code armor/*} 里搬了 10 件最有辨识度的，
 * 但**不用资源包**：全部靠原版材质 + 显示名 + Lore + 附魔光效区分，
 * 身份用 PersistentDataContainer 的 {@code ww_item} 键标记，不吃改名也不怕被复制。
 *
 * <p>效果分成四类：武器命中、右键使用、护甲被动、一次性消耗。
 */
public final class CustomItems {

    /** 物品类型。 */
    public enum ItemType {
        /** 破舰者：高基础伤害，对穿甲目标额外加成。 */
        BREACHER(Material.DIAMOND_SWORD, "破舰者", Kind.WEAPON, "命中时额外造成 1.5 点真实伤害"),
        /** 锋针：命中给对手短暂缓慢。 */
        NEEDLE(Material.IRON_SWORD, "锋针", Kind.WEAPON, "命中时使目标缓慢 I 3 秒"),
        /** 猎人护身符：连续命中叠伤害。 */
        HUNTER_CHARM(Material.RABBIT_FOOT, "猎人护身符", Kind.PASSIVE, "每命中一次永久 +0.15 攻击伤害（上限 3）"),

        /** 回魂灯：治疗周围队友。 */
        SOUL_LAMP(Material.SOUL_LANTERN, "回魂灯", Kind.USE, "右键点地：治疗 6 格内队友各 4 点"),
        /** 灵魂储囊：加能量。 */
        SOUL_POUCH(Material.BUNDLE, "灵魂储囊", Kind.USE, "右键：立刻获得 20 点职业能量"),
        /** 微缩炸弹：定点爆破。 */
        MICRO_BOMB(Material.TNT, "微缩炸弹", Kind.USE, "右键丢出：在落点造成 5 点范围真伤"),
        /** 火焰冲击：点燃周围敌人。 */
        FLAME_IMPACT(Material.BLAZE_POWDER, "火焰冲击", Kind.USE, "右键：点燃 5 格内敌人 4 秒"),
        /** 冰封药水：减速。 */
        FROST_FLASK(Material.SPLASH_POTION, "冰封药水", Kind.USE, "右键：使 5 格内敌人缓慢 II 6 秒"),

        /** 恶魔头盔：低血时给力量。 */
        DEMON_HELM(Material.NETHERITE_HELMET, "恶魔头盔", Kind.ARMOR, "血量低于 40% 时获得力量 I"),
        /** 龙麟项链：周期性抗性。 */
        DRAGON_NECKLACE(Material.NETHERITE_CHESTPLATE, "龙鳞项链", Kind.ARMOR, "每 10 秒获得抗性 I 3 秒"),

        // ===== 以下对齐数据包 weapon/* armor/* 的其余条目 =====
        SPEAR_SIMPLE(Material.IRON_SPEAR, "简易长矛", Kind.WEAPON, "更长的攻击距离，命中附加 1 点真伤"),
        NETBREAKER(Material.IRON_AXE, "裂网者", Kind.WEAPON, "命中时清掉目标的速度增益"),
        IRON_GAUNTLET(Material.IRON_INGOT, "铁拳套", Kind.WEAPON, "命中时叠加缓慢"),
        CORROSION_POTION(Material.SPLASH_POTION, "腐蚀药水", Kind.USE, "右键：5 格内敌人护甲耐久 -50"),
        GLOW_ARROW(Material.SPECTRAL_ARROW, "光灵箭", Kind.USE, "右键：5 格内敌人发光 10 秒"),
        THUNDER_FLASK(Material.SPLASH_POTION, "烈焰药水", Kind.USE, "右键：点燃 5 格内敌人"),
        HERALD_HORN(Material.GOAT_HORN, "回春角笛", Kind.USE, "右键：治疗自己 5 点"),
        SMOKE_BOMB(Material.GUNPOWDER, "烟雾弹", Kind.USE, "右键：隐身 5 秒"),
        WIND_CHARM(Material.BREEZE_ROD, "风之护符", Kind.USE, "右键：跳跃提升 II + 速度 II 8 秒"),
        WITHER_SKULL_ITEM(Material.WITHER_SKELETON_SKULL, "凋零头骨", Kind.USE, "右键：5 格内敌人凋零 II 6 秒"),
        NUCLEAR_ARMOR(Material.NETHERITE_CHESTPLATE, "核纳", Kind.ARMOR, "受到近战伤害时反弹 1 点"),
        VINE_ARMOR(Material.LEATHER_CHESTPLATE, "藤甲", Kind.ARMOR, "受到箭伤时伤害减半"),
        FEATHER_BOOTS(Material.NETHERITE_BOOTS, "耐摔鞋", Kind.ARMOR, "免疫摔落伤害"),
        EDEN_SHARD(Material.ECHO_SHARD, "伊甸碎片", Kind.PASSIVE, "站在地图中央 10 格内每秒回 0.5 点"),
        CROWN_OF_KINGS(Material.GOLDEN_HELMET, "加冕之冠", Kind.ARMOR, "击杀后获得速度 II 5 秒");

        private final Material material;
        private final String chineseName;
        private final Kind kind;
        private final String description;

        ItemType(Material material, String chineseName, Kind kind, String description) {
            this.material = material;
            this.chineseName = chineseName;
            this.kind = kind;
            this.description = description;
        }

        public Material material() {
            return material;
        }

        public String chineseName() {
            return chineseName;
        }

        public Kind kind() {
            return kind;
        }

        public String description() {
            return description;
        }

        public static ItemType parse(String raw) {
            if (raw == null) {
                return null;
            }
            String key = raw.trim().toUpperCase(Locale.ROOT);
            for (ItemType type : values()) {
                if (type.name().equals(key) || type.chineseName.equals(raw.trim())) {
                    return type;
                }
            }
            return null;
        }
    }

    /** 物品行为分类。 */
    public enum Kind {
        WEAPON, USE, PASSIVE, ARMOR
    }

    private final WarwallPlugin plugin;
    private final NamespacedKey key;

    public CustomItems(WarwallPlugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "ww_item");
    }

    // ===== 构造与识别 =====

    /** 造一件自定义物品。 */
    public ItemStack create(ItemType type) {
        ItemStack item = new ItemStack(type.material(), 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.itemName(Component.text("§b" + type.chineseName()));
            meta.lore(List.of(
                    Component.text("§7" + type.description()),
                    Component.text("§8战墙专属 · 不可合成")));
            meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, type.name());
            if (type.kind() == Kind.WEAPON || type.kind() == Kind.ARMOR) {
                meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES);
                meta.addEnchant(Enchantment.UNBREAKING, 3, true);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    /** 识别一件物品是不是自定义物品。 */
    public ItemType typeOf(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }
        String raw = meta.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        return raw == null ? null : ItemType.parse(raw);
    }

    // ===== 效果 =====

    /** 武器命中效果；由战斗监听器在真实命中后调用。 */
    public void onWeaponHit(Player attacker, Player victim, ItemStack weapon) {
        ItemType type = typeOf(weapon);
        if (type == null) {
            return;
        }
        switch (type) {
            case BREACHER -> {
                double after = Math.max(0, victim.getHealth() - 1.5);
                victim.setHealth(after <= 0 ? 0 : after);
            }
            case NEEDLE -> victim.addPotionEffect(new PotionEffect(
                    PotionEffectType.SLOWNESS, 60, 0, true, false, false));
            case HUNTER_CHARM -> {
                // 叠层实现成「每命中一次给自己一点攻击伤害」，用属性上限兜住
                var attribute = attacker.getAttribute(org.bukkit.attribute.Attribute.ATTACK_DAMAGE);
                if (attribute != null && attribute.getBaseValue() < 12.0) {
                    attribute.setBaseValue(attribute.getBaseValue() + 0.15);
                }
            }
            default -> {
                // 其余物品没有命中效果
            }
        }
    }

    /** 右键使用效果；返回是否消耗掉这件物品。 */
    public boolean onUse(Player player, ItemType type) {
        TeamColor team = plugin.teams().teamOf(player);
        // 新一批使用类物品走 onUseExtended，保持原有分支不动
        switch (type) {
            case CORROSION_POTION, GLOW_ARROW, THUNDER_FLASK, HERALD_HORN,
                 SMOKE_BOMB, WIND_CHARM, WITHER_SKULL_ITEM -> {
                return onUseExtended(player, type);
            }
            default -> {
                // 落到下面的原有分支
            }
        }
        switch (type) {
            case SOUL_LAMP -> {
                for (Player mate : player.getWorld().getPlayers()) {
                    if (plugin.teams().teamOf(mate) == team
                            && mate.getLocation().distanceSquared(player.getLocation()) <= 36) {
                        mate.setHealth(Math.min(mate.getMaxHealth(), mate.getHealth() + 4));
                    }
                }
                return true;
            }
            case SOUL_POUCH -> {
                plugin.kits().addEnergy(player, 20);
                return true;
            }
            case FLAME_IMPACT -> {
                for (Player target : player.getWorld().getPlayers()) {
                    if (plugin.teams().teamOf(target) != team
                            && target.getLocation().distanceSquared(player.getLocation()) <= 25) {
                        target.setFireTicks(80);
                    }
                }
                return true;
            }
            case FROST_FLASK -> {
                for (Player target : player.getWorld().getPlayers()) {
                    if (plugin.teams().teamOf(target) != team
                            && target.getLocation().distanceSquared(player.getLocation()) <= 25) {
                        target.addPotionEffect(new PotionEffect(
                                PotionEffectType.SLOWNESS, 120, 1, true, false, false));
                    }
                }
                return true;
            }
            case MICRO_BOMB -> {
                for (Player target : player.getWorld().getPlayers()) {
                    if (plugin.teams().teamOf(target) != team
                            && target.getLocation().distanceSquared(player.getLocation()) <= 25) {
                        double after = Math.max(0, target.getHealth() - 5);
                        target.setHealth(after <= 0 ? 0 : after);
                    }
                }
                player.getWorld().createExplosion(player.getLocation(), 0f, false, false);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** 区域效果小工具：给附近的人上药水。 */
    private void areaEffect(Player source, TeamColor team, boolean allies,
                            PotionEffectType effect, int ticks, int amplifier, double radius) {
        for (Player target : source.getWorld().getPlayers()) {
            if (target.equals(source)) {
                continue;
            }
            TeamColor targetTeam = plugin.teams().teamOf(target);
            if (targetTeam == null || (targetTeam == team) != allies) {
                continue;
            }
            if (target.getLocation().distanceSquared(source.getLocation()) > radius * radius) {
                continue;
            }
            target.addPotionEffect(new PotionEffect(effect, ticks, amplifier, true, false, false));
        }
    }

    /** 新一批使用类物品的效果；返回是否消耗。 */
    private boolean onUseExtended(Player player, ItemType type) {
        TeamColor team = plugin.teams().teamOf(player);
        switch (type) {
            case CORROSION_POTION -> {
                for (Player target : player.getWorld().getPlayers()) {
                    if (plugin.teams().teamOf(target) == team || target.equals(player)) {
                        continue;
                    }
                    if (target.getLocation().distanceSquared(player.getLocation()) > 25) {
                        continue;
                    }
                    for (org.bukkit.inventory.ItemStack armor
                            : target.getInventory().getArmorContents()) {
                        if (armor != null && armor.getType().getMaxDurability() > 0) {
                            armor.setDurability((short) Math.min(armor.getType().getMaxDurability() - 1,
                                    armor.getDurability() + 50));
                        }
                    }
                    target.updateInventory();
                }
                return true;
            }
            case GLOW_ARROW -> {
                areaEffect(player, team, false, PotionEffectType.GLOWING, 200, 0, 5.0);
                return true;
            }
            case THUNDER_FLASK -> {
                for (Player target : player.getWorld().getPlayers()) {
                    if (plugin.teams().teamOf(target) != team
                            && target.getLocation().distanceSquared(player.getLocation()) <= 25) {
                        target.setFireTicks(80);
                    }
                }
                return true;
            }
            case HERALD_HORN -> {
                player.setHealth(Math.min(player.getMaxHealth(), player.getHealth() + 5));
                return true;
            }
            case SMOKE_BOMB -> {
                player.addPotionEffect(new PotionEffect(
                        PotionEffectType.INVISIBILITY, 100, 0, true, false, false));
                return true;
            }
            case WIND_CHARM -> {
                player.addPotionEffect(new PotionEffect(
                        PotionEffectType.JUMP_BOOST, 160, 1, true, false, false));
                player.addPotionEffect(new PotionEffect(
                        PotionEffectType.SPEED, 160, 1, true, false, false));
                return true;
            }
            case WITHER_SKULL_ITEM -> {
                areaEffect(player, team, false, PotionEffectType.WITHER, 120, 1, 5.0);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** 护甲被动；由定时器每秒调用一次。 */
    public void tickArmor(Player player) {
        ItemStack helmet = player.getInventory().getHelmet();
        if (typeOf(helmet) == ItemType.DEMON_HELM
                && player.getHealth() <= player.getMaxHealth() * 0.4) {
            player.addPotionEffect(new PotionEffect(
                    PotionEffectType.STRENGTH, 60, 0, true, false, false));
        }
        ItemStack chest = player.getInventory().getChestplate();
        if (typeOf(chest) == ItemType.DRAGON_NECKLACE && System.currentTimeMillis() % 10_000 < 1_000) {
            player.addPotionEffect(new PotionEffect(
                    PotionEffectType.RESISTANCE, 60, 0, true, false, false));
        }
    }

    /** 随机发一件，用于凋零/核心被摧毁时的奖励。 */
    public ItemStack randomItem() {
        ItemType[] all = ItemType.values();
        return create(all[(int) (Math.random() * all.length)]);
    }
}
