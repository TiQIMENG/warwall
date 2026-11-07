package cn.infinite.warwall.kit;

import cn.infinite.warwall.WarwallPlugin;
import cn.infinite.warwall.config.WarwallConfig;
import cn.infinite.warwall.team.TeamColor;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * 职业系统。
 *
 * <p><b>只有一条发育曲线，没有模式开关。</b>职业提供的是「身份 + 少量标志性起步物 + 主动技能」，
 * 铁装、钻石装、附魔、药水全部由玩家在准备期自己挖矿冶炼——这是 UHC 的发育手感；
 * 而四队凋零、墙倒开战、凋零存活才可复活这些是战墙的骨架。
 *
 * <p>数值按 1.21.11 的 20 HP 与 1.6 击/秒重标定过，见 docs/BALANCE-1.21.11.md。
 */
public final class KitManager {

    private final WarwallPlugin plugin;

    private final Map<UUID, KitId> chosen = new HashMap<>();
    private final Map<UUID, Integer> energy = new HashMap<>();

    public KitManager(WarwallPlugin plugin) {
        this.plugin = plugin;
    }

    /** 8 个职业。{@code icon} 同时作为技能物品的材质。 */
    public enum KitId {
        ZOMBIE("僵尸", Material.GLISTERING_MELON_SLICE),
        SKELETON("骷髅", Material.BOW),
        HEROBRINE("恐惧魔王", Material.NETHER_STAR),
        CREEPER("爬行者", Material.GUNPOWDER),
        ENDERMAN("末影人", Material.ENDER_PEARL),
        GOLEM("傀儡", Material.IRON_BLOCK),
        MINER("矿工", Material.DIAMOND_PICKAXE),
        BUILDER("建筑师", Material.SCAFFOLDING);

        private final String chineseName;
        private final Material icon;

        KitId(String chineseName, Material icon) {
            this.chineseName = chineseName;
            this.icon = icon;
        }

        public String chineseName() {
            return chineseName;
        }

        public Material icon() {
            return icon;
        }

        public static KitId parse(String raw) {
            if (raw == null) {
                return null;
            }
            String key = raw.trim().toUpperCase(Locale.ROOT);
            for (KitId kit : values()) {
                if (kit.name().equals(key) || kit.chineseName.equals(raw.trim())) {
                    return kit;
                }
            }
            return null;
        }
    }

    public void select(Player player, KitId kit) {
        chosen.put(player.getUniqueId(), kit);
    }

    public KitId kitOf(Player player) {
        return chosen.getOrDefault(player.getUniqueId(), KitId.ZOMBIE);
    }

    // ===== 能量 =====

    public int energyOf(Player player) {
        return energy.getOrDefault(player.getUniqueId(), 0);
    }

    public void setEnergy(Player player, int value) {
        int clamped = Math.max(0, Math.min(100, value));
        energy.put(player.getUniqueId(), clamped);
        plugin.ui().actionBar(player, "&e能量 &f" + clamped + "&7/100");
    }

    public void addEnergy(Player player, int amount) {
        int current = energyOf(player);
        if (current >= 100) {
            plugin.ui().actionBar(player, "&a技能已就绪，右键技能物品释放。");
            return;
        }
        int next = Math.min(100, current + amount);
        energy.put(player.getUniqueId(), next);
        if (next >= 100) {
            plugin.ui().actionBar(player, plugin.config()
                    .message("ability-ready", "&a技能已就绪，右键技能物品释放。"));
        } else {
            plugin.ui().actionBar(player, "&e能量 &f" + next + "&7/100");
        }
    }

    public boolean consumeEnergy(Player player) {
        if (energyOf(player) < 100) {
            return false;
        }
        energy.put(player.getUniqueId(), 0);
        return true;
    }

    /** 每刀能量：与 Hypixel 口径对齐（100 能量制）。 */
    private int energyPerHit(KitId kit) {
        return switch (kit) {
            case ZOMBIE -> 12;
            case SKELETON -> 25;
            case HEROBRINE -> 25;
            case CREEPER -> 20;
            case ENDERMAN -> 20;
            case GOLEM -> 10;
            case MINER -> 14;
            case BUILDER -> 14;
        };
    }

    public void addEnergyForHit(Player player) {
        addEnergy(player, energyPerHit(kitOf(player)));
    }

    // ===== 开局物资 =====

    /**
     * 发放开局物资。
     *
     * <p>通用部分只有木镐、木斧、石剑、口粮与火把；职业再补一两件「能让它在发育期就有用」的东西。
     * 想让某个职业更强，应该调它的技能与被动，而不是发更多装备——否则 UHC 的发育曲线就没了。
     */
    public void giveKit(Player player, TeamColor team) {
        KitId kit = kitOf(player);
        PlayerInventory inventory = player.getInventory();
        inventory.clear();

        player.setMaxHealth(plugin.config().playerMaxHealth());
        player.setHealth(player.getMaxHealth());
        player.setFoodLevel(20);

        for (Material material : plugin.config().starterItems()) {
            inventory.addItem(new ItemStack(material, 1));
        }
        inventory.addItem(new ItemStack(Material.COOKED_BEEF, plugin.config().starterFood()));
        inventory.addItem(new ItemStack(Material.TORCH, plugin.config().starterTorches()));

        switch (kit) {
            case MINER -> {
                // 矿工在发育期的价值：更快进入挖矿循环。
                inventory.addItem(new ItemStack(Material.IRON_PICKAXE, 1));
                inventory.addItem(new ItemStack(Material.TORCH, 16));
            }
            case BUILDER -> {
                // 建筑师起步就能封路、加固核心。
                inventory.addItem(new ItemStack(Material.COBBLESTONE, 32));
                inventory.addItem(new ItemStack(Material.SCAFFOLDING, 16));
            }
            case SKELETON -> {
                inventory.addItem(new ItemStack(Material.BOW, 1));
                inventory.addItem(new ItemStack(Material.ARROW, 16));
                inventory.addItem(new ItemStack(Material.STRING, 2));
            }
            case ENDERMAN -> inventory.addItem(new ItemStack(Material.ENDER_PEARL, 1));
            case GOLEM -> inventory.addItem(new ItemStack(Material.SHIELD, 1));
            case ZOMBIE -> inventory.addItem(new ItemStack(Material.GOLDEN_APPLE, 1));
            default -> {
                // 其余职业靠技能与发育拉开差距
            }
        }

        // 技能物品固定放第 9 格
        inventory.setItem(8, abilityItem(kit, team));
        energy.put(player.getUniqueId(), 0);
    }

    private ItemStack abilityItem(KitId kit, TeamColor team) {
        ItemStack item = new ItemStack(kit.icon());
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.itemName(Component.text(WarwallConfig.colorize(
                    team.chatColor() + "技能·" + kit.chineseName())));
            meta.lore(List.of(
                    Component.text(WarwallConfig.colorize("&7能量满 100 后右键释放")),
                    Component.text(WarwallConfig.colorize("&7当前职业：" + kit.chineseName()))));
            if (plugin.config().hideAttributes()) {
                meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    /** 判断某个物品是不是技能物品。 */
    public boolean isAbilityItem(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.hasItemName() && meta.itemName() != null
                && PlainTextComponentSerializer.plainText().serialize(meta.itemName()).contains("技能");
    }

    // ===== 主动技能 =====

    /** 释放主动技能；能量不足返回 false。 */
    public boolean activateAbility(Player player) {
        if (!consumeEnergy(player)) {
            plugin.ui().actionBar(player, "&c能量不足");
            return false;
        }
        switch (kitOf(player)) {
            case ZOMBIE -> {
                heal(player, 3.0);
                for (Player mate : plugin.teams().onlineOf(plugin.teams().teamOf(player))) {
                    if (!mate.equals(player)
                            && mate.getLocation().distanceSquared(player.getLocation()) <= 25) {
                        heal(mate, 2.0);
                    }
                }
                player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_ZOMBIE_AMBIENT, 1f, 1.4f);
            }
            case HEROBRINE -> {
                aoeTrueDamage(player, 5.0, 3.0);
                player.getWorld().strikeLightningEffect(player.getLocation());
            }
            case CREEPER -> {
                aoeTrueDamage(player, 6.0, 6.0);
                player.getWorld().createExplosion(player.getLocation(), 0f, false, false);
            }
            case GOLEM -> {
                aoeTrueDamage(player, 4.5, 3.0);
                player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_IRON_GOLEM_ATTACK, 1f, 0.8f);
            }
            case SKELETON -> {
                aoeTrueDamage(player, 3.0, 3.0);
                player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_ARROW_HIT, 1f, 1.2f);
            }
            case ENDERMAN -> {
                org.bukkit.util.Vector direction = player.getLocation().getDirection()
                        .normalize().multiply(18);
                player.teleport(player.getLocation().add(direction));
                player.addPotionEffect(new PotionEffect(
                        PotionEffectType.SPEED, 100, 2, true, false, false));
            }
            case MINER, BUILDER -> player.addPotionEffect(new PotionEffect(
                    PotionEffectType.HASTE, 200, 2, true, false, false));
            default -> {
                // 无
            }
        }
        return true;
    }

    private void heal(Player player, double amount) {
        player.setHealth(Math.min(player.getMaxHealth(), player.getHealth() + amount));
    }

    /** 对半径内敌人造成真伤（无视护甲），UHC 风格下这类伤害很致命。 */
    private void aoeTrueDamage(Player source, double radius, double damage) {
        for (Player target : source.getWorld().getPlayers()) {
            TeamColor targetTeam = plugin.teams().teamOf(target);
            TeamColor sourceTeam = plugin.teams().teamOf(source);
            if (targetTeam == null || targetTeam == sourceTeam || target.isDead()) {
                continue;
            }
            if (target.getLocation().distanceSquared(source.getLocation()) > radius * radius) {
                continue;
            }
            double after = Math.max(0, target.getHealth() - damage);
            if (after <= 0) {
                target.setHealth(0);
            } else {
                target.setHealth(after);
            }
            plugin.stats().onDamage(source, damage);
        }
    }

    public void clearKit(Player player) {
        player.getInventory().clear();
        energy.remove(player.getUniqueId());
    }

    public void forget(UUID uuid) {
        energy.remove(uuid);
        hitCounter.remove(uuid);
        passiveCooldown.remove(uuid);
    }

    // ===== 职业被动 =====

    /** 连击计数（Herobrine）与被动冷却时间戳（毫秒）。 */
    private final Map<UUID, Integer> hitCounter = new HashMap<>();
    private final Map<UUID, Long> passiveCooldown = new HashMap<>();

    /**
     * 命中后的被动。由战斗监听器在「真实命中」之后调用一次。
     *
     * <p>数值都在 20 HP 尺度上，且刻意做得比主动技能弱：被动是身份感，不是胜负手。
     */
    public void onHit(Player attacker, Player victim) {
        switch (kitOf(attacker)) {
            case HEROBRINE -> {
                // 连击：每 3 次命中给一次速度 + 再生，鼓励持续贴脸输出。
                int streak = hitCounter.merge(attacker.getUniqueId(), 1, Integer::sum);
                if (streak % 3 == 0) {
                    attacker.addPotionEffect(new PotionEffect(
                            PotionEffectType.SPEED, 60, 1, true, false, false));
                    attacker.addPotionEffect(new PotionEffect(
                            PotionEffectType.REGENERATION, 100, 0, true, false, false));
                }
            }
            case MINER -> addEnergy(attacker, 2);
            default -> {
                // 其余职业没有命中被动
            }
        }
    }

    /**
     * 受伤后的被动。
     *
     * <p>Zombie 的「坚韧」与 Creeper 的「意志」共用一条冷却，避免连续触发把血线拉回来。
     */
    public void onDamaged(Player victim, Player attacker) {
        long now = System.currentTimeMillis();
        long readyAt = passiveCooldown.getOrDefault(victim.getUniqueId(), 0L);
        if (now < readyAt) {
            return;
        }
        switch (kitOf(victim)) {
            case ZOMBIE -> {
                // 坚韧：挨打后短促再生，等价于「血厚」的手感。
                victim.addPotionEffect(new PotionEffect(
                        PotionEffectType.REGENERATION, 160, 0, true, false, false));
                passiveCooldown.put(victim.getUniqueId(), now + 15_000L);
            }
            case CREEPER -> {
                if (victim.getHealth() <= victim.getMaxHealth() * 0.5) {
                    // 意志：残血反打的本钱。
                    victim.addPotionEffect(new PotionEffect(
                            PotionEffectType.SPEED, 240, 1, true, false, false));
                    victim.addPotionEffect(new PotionEffect(
                            PotionEffectType.REGENERATION, 240, 0, true, false, false));
                    passiveCooldown.put(victim.getUniqueId(), now + 20_000L);
                }
            }
            case GOLEM -> victim.addPotionEffect(new PotionEffect(
                    PotionEffectType.RESISTANCE, 60, 0, true, false, false));
            default -> {
                // 无
            }
        }
    }

    /** 远程命中：Skeleton 回收箭矢。 */
    public void onProjectileHit(Player attacker) {
        if (kitOf(attacker) == KitId.SKELETON) {
            attacker.getInventory().addItem(new ItemStack(Material.ARROW, 2));
        }
    }

    /** 击杀后的被动。 */
    public void onKill(Player killer) {
        switch (kitOf(killer)) {
            case ENDERMAN -> heal(killer, 3.0);
            case HEROBRINE -> killer.addPotionEffect(new PotionEffect(
                    PotionEffectType.STRENGTH, 120, 0, true, false, false));
            case ZOMBIE -> heal(killer, 1.0);
            default -> {
                // 无
            }
        }
    }

    /** 破坏方块：Miner 的「矿脉」被动，挖矿本身攒能量。 */
    public void onBlockBroken(Player player) {
        if (kitOf(player) == KitId.MINER) {
            addEnergy(player, 1);
        }
    }

    /** 放置方块：Builder 的「快速搭建」被动。 */
    public void onBlockPlaced(Player player) {
        if (kitOf(player) == KitId.BUILDER) {
            addEnergy(player, 1);
        }
    }
}
