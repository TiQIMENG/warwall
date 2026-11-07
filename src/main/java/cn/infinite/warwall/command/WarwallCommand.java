package cn.infinite.warwall.command;

import cn.infinite.warwall.WarwallPlugin;
import cn.infinite.warwall.arena.Box;
import cn.infinite.warwall.arena.WarwallArena;
import cn.infinite.warwall.core.MatchState;
import cn.infinite.warwall.team.TeamColor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

/**
 * /warwall 命令树。
 *
 * <p>分三类：玩家命令（join/kit/leave/status）、选区命令（setup ...，需要 warwall.setup）、
 * 管理命令（start/stop/reload/debug ...，需要 warwall.admin）。
 *
 * <p>所有回显都带 {@code [战墙]} 前缀，控制台可执行除选区外的全部子命令。
 */
public final class WarwallCommand implements CommandExecutor, TabCompleter {

    private final WarwallPlugin plugin;

    /** 选区：第一点 / 第二点，单位为内存态，不落盘。 */
    private Location pos1;
    private Location pos2;

    public WarwallCommand(WarwallPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            send(sender, "&e用法：/" + label + " " + "help|join|leave|kit|status|start|stop|reload|setup|debug");
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        switch (sub) {
            case "help" -> help(sender);
            case "status" -> {
                send(sender, "&e" + plugin.controller().statusLine());
                return true;
            }
            case "start" -> {
                if (!requireAdmin(sender)) {
                    return true;
                }
                return plugin.controller().startMatch();
            }
            case "stop" -> {
                if (!requireAdmin(sender)) {
                    return true;
                }
                plugin.controller().stopMatch();
                send(sender, "&e本局已中止。");
                return true;
            }
            case "reload" -> {
                if (!requireAdmin(sender)) {
                    return true;
                }
                plugin.reloadAll();
                send(sender, "&a配置已重载。");
                return true;
            }
            case "coins" -> {
                if (!(sender instanceof Player player)) {
                    send(sender, "&c只能由玩家执行。");
                    return true;
                }
                send(sender, "&6金币：&f" + plugin.economy().coinsOf(player));
                return true;
            }
            case "upgrade" -> {
                if (!(sender instanceof Player player)) {
                    send(sender, "&c只能由玩家执行。");
                    return true;
                }
                return upgrade(player, rest);
            }
            case "shop" -> {
                if (!(sender instanceof Player player)) {
                    send(sender, "&c只能由玩家执行。");
                    return true;
                }
                return showShop(player);
            }
            case "kit" -> {
                if (!(sender instanceof Player player) || rest.length == 0) {
                    send(sender, "&c用法：/warwall kit <zombie|skeleton|herobrine|creeper|enderman|golem|miner|builder>");
                    return true;
                }
                cn.infinite.warwall.kit.KitManager.KitId kit =
                        cn.infinite.warwall.kit.KitManager.KitId.parse(rest[0]);
                if (kit == null) {
                    send(sender, "&c未知职业：" + rest[0]);
                    return true;
                }
                plugin.kits().select(player, kit);
                send(sender, "&a已选择职业：" + kit.chineseName() + "（等级 "
                        + plugin.economy().levelOf(player, kit.name()) + "）");
                return true;
            }
            case "give" -> {
                if (!requireAdmin(sender)) {
                    return true;
                }
                return giveItem(sender, rest);
            }
            case "building" -> {
                if (!requireAdmin(sender)) {
                    return true;
                }
                return giveBuilding(sender, rest);
            }
            case "setup" -> {
                if (!requireSetup(sender)) {
                    return true;
                }
                return handleSetup(sender, rest);
            }
            case "debug" -> {
                if (!requireAdmin(sender)) {
                    return true;
                }
                return handleDebug(sender, rest);
            }
            default -> {
                send(sender, "&c未知子命令：" + sub + "，试 /warwall help");
                return true;
            }
        }
        return true;
    }

    // ===== 经济 / 物品 / 建筑 =====

    private boolean upgrade(Player player, String[] args) {
        if (args.length < 1) {
            send(player, "&c用法：/warwall upgrade <职业 id>");
            return true;
        }
        cn.infinite.warwall.kit.KitManager.KitId kit =
                cn.infinite.warwall.kit.KitManager.KitId.parse(args[0]);
        if (kit == null) {
            send(player, "&c未知职业：" + args[0]);
            return true;
        }
        int cost = plugin.economy().upgradeCost(player, kit.name());
        if (cost < 0) {
            send(player, "&e" + kit.chineseName() + " 已经是满级了。");
            return true;
        }
        int level = plugin.economy().upgrade(player, kit.name());
        if (level < 0) {
            send(player, "&c金币不足，升级到 " + (plugin.economy().levelOf(player, kit.name()) + 1)
                    + " 级需要 " + cost + " 金币。");
            return true;
        }
        send(player, "&a" + kit.chineseName() + " 升到 " + level + " 级。");
        return true;
    }

    private boolean showShop(Player player) {
        send(player, "&6===== 职业升级 =====");
        send(player, "&7金币：&f" + plugin.economy().coinsOf(player));
        for (cn.infinite.warwall.kit.KitManager.KitId kit
                : cn.infinite.warwall.kit.KitManager.KitId.values()) {
            int level = plugin.economy().levelOf(player, kit.name());
            int cost = plugin.economy().upgradeCost(player, kit.name());
            send(player, "&e" + kit.chineseName() + " &7等级 &f" + level
                    + (cost < 0 ? " &a(满级)" : " &7升级需 &f" + cost + " 金币"));
        }
        send(player, "&7用 /warwall upgrade <职业 id> 升级。");
        return true;
    }

    private boolean giveItem(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player) || args.length < 1) {
            send(sender, "&c用法：/warwall give <物品 id>（玩家执行）");
            return true;
        }
        cn.infinite.warwall.item.CustomItems.ItemType type =
                cn.infinite.warwall.item.CustomItems.ItemType.parse(args[0]);
        if (type == null) {
            send(sender, "&c未知物品：" + args[0]);
            return true;
        }
        player.getInventory().addItem(plugin.items().create(type));
        send(sender, "&a已获得：" + type.chineseName());
        return true;
    }

    private boolean giveBuilding(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player) || args.length < 1) {
            send(sender, "&c用法：/warwall building <battle-court|farm|war-banner|tesla-tower>");
            return true;
        }
        cn.infinite.warwall.building.BuildingManager.BuildingType type =
                cn.infinite.warwall.building.BuildingManager.BuildingType.parse(args[0]);
        if (type == null) {
            send(sender, "&c未知建筑：" + args[0]);
            return true;
        }
        boolean placed = plugin.buildings().place(player, type);
        send(sender, placed ? "&a已放置：" + type.chineseName() : "&c放置失败。");
        return true;
    }

    // ===== setup =====

    private boolean handleSetup(CommandSender sender, String[] args) {
        if (args.length == 0) {
            send(sender, "&e/warwall setup create|select|pos1|pos2|lobby|spectator|team|center|wall|unbreakable|mining|nobuild|validate|save");
            return true;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "create" -> {
                if (args.length < 2) {
                    send(sender, "&c用法：/warwall setup create <id> [world]");
                    return true;
                }
                String id = args[1];
                String world = args.length > 2 ? args[2] : (sender instanceof Player p ? p.getWorld().getName() : "world");
                ConfigurationSection section = plugin.arenaStore().root()
                        .createSection("arenas." + id);
                section.set("enabled", true);
                section.set("world", world);
                section.set("min-players", 8);
                section.set("max-players", 100);
                plugin.arenaStore().save();
                send(sender, "&a已创建竞技场 " + id + "（世界 " + world + "），先 /warwall setup select " + id);
                return true;
            }
            case "select" -> {
                if (args.length < 2) {
                    send(sender, "&c用法：/warwall setup select <id>");
                    return true;
                }
                List<String> errors = new ArrayList<>();
                WarwallArena arena = plugin.arenaStore().read(args[1], errors);
                if (arena == null) {
                    errors.forEach(e -> send(sender, "&c" + e));
                    return true;
                }
                plugin.selectArena(arena);
                send(sender, "&a当前竞技场：" + arena.id()
                        + (errors.isEmpty() ? "（校验通过）" : "（仍有 " + errors.size() + " 项待补）"));
                errors.forEach(e -> send(sender, "&e - " + e));
                return true;
            }
            case "pos1", "pos2" -> {
                return setPos(sender, action);
            }
            case "clear" -> {
                pos1 = null;
                pos2 = null;
                send(sender, "&e选区已清空。");
                return true;
            }
            case "lobby", "spectator", "center" -> {
                return writePointHere(sender, action);
            }
            case "team" -> {
                return handleTeam(sender, args);
            }
            case "border" -> {
                if (args.length >= 4 && args[1].equalsIgnoreCase("radius")) {
                    plugin.getConfig().set("border.base-radius", Double.parseDouble(args[2]));
                    plugin.saveConfig();
                    send(sender, "&a边界基础半径已改为 " + args[2] + "（全局值，config.yml border.base-radius）");
                    return true;
                }
                if (args.length >= 2 && args[1].equalsIgnoreCase("center")) {
                    return writePoint(sender, "border-center", true);
                }
                send(sender, "&c用法：/warwall setup border center | border radius <n>");
                return true;
            }
            case "wall", "unbreakable", "mining", "nobuild" -> {
                return handleBox(sender, action, args);
            }
            case "validate" -> {
                return validate(sender);
            }
            case "save" -> {
                boolean ok = plugin.arenaStore().save();
                send(sender, ok ? "&aarenas.yml 已保存。" : "&c保存失败，请看控制台日志。");
                return true;
            }
            default -> {
                send(sender, "&c未知的 setup 子命令：" + action);
                return true;
            }
        }
    }

    private boolean setPos(CommandSender sender, String which) {
        if (!(sender instanceof Player player)) {
            send(sender, "&c选区只能由玩家执行。");
            return true;
        }
        if ("pos1".equals(which)) {
            pos1 = player.getTargetBlockExact(64) == null
                    ? player.getLocation().toBlockLocation()
                    : player.getTargetBlockExact(64).getLocation();
        } else {
            pos2 = player.getTargetBlockExact(64) == null
                    ? player.getLocation().toBlockLocation()
                    : player.getTargetBlockExact(64).getLocation();
        }
        send(sender, "&a已设置 " + which + "：" + (which.equals("pos1") ? pos1 : pos2));
        return true;
    }

    private boolean handleTeam(CommandSender sender, String[] args) {
        if (args.length < 3) {
            send(sender, "&c用法：/warwall setup team <red|yellow|green|blue> <spawn|respawn|wither|nexus>");
            return true;
        }
        TeamColor team = TeamColor.parse(args[1]);
        if (team == null) {
            send(sender, "&c未知队伍：" + args[1]);
            return true;
        }
        String field = args[2].toLowerCase(Locale.ROOT);
        if ("nexus".equals(field)) {
            // 核心是一个区域，用 pos1/pos2 选区写入，而不是玩家站位。
            Box box = currentBox(sender);
            if (box == null) {
                return true;
            }
            String path = "arenas." + currentId() + ".teams." + team.id() + ".nexus";
            ConfigurationSection section = plugin.arenaStore().root().createSection(path);
            section.set("min", Map.of("x", box.minX(), "y", box.minY(), "z", box.minZ()));
            section.set("max", Map.of("x", box.maxX(), "y", box.maxY(), "z", box.maxZ()));
            plugin.arenaStore().save();
            send(sender, "&a已写入 " + team.chineseName() + " 的核心区域：" + box.describe());
            return true;
        }
        if (!"spawn".equals(field) && !"respawn".equals(field) && !"wither".equals(field)) {
            send(sender, "&c未知字段：" + field + "，可用 spawn|respawn|wither|nexus");
            return true;
        }
        return writePoint(sender, "arenas." + currentId() + ".teams." + team.id() + "." + field,
                "spawn".equals(field) || "respawn".equals(field));
    }

    private boolean handleBox(CommandSender sender, String action, String[] args) {
        String key = switch (action) {
            case "wall" -> "walls";
            case "unbreakable" -> "unbreakable";
            case "mining" -> "mining-zones";
            case "nobuild" -> "no-build-zones";
            default -> throw new IllegalStateException(action);
        };
        List<Object> list = new ArrayList<>(plugin.arenaStore().root()
                .getMapList("arenas." + currentId() + "." + key));

        if (args.length >= 2 && args[1].equalsIgnoreCase("add")) {
            Box box = currentBox(sender);
            if (box == null) {
                return true;
            }
            list.add(cn.infinite.warwall.arena.ArenaStore.boxToMap(box));
            plugin.arenaStore().root().set("arenas." + currentId() + "." + key, list);
            plugin.arenaStore().save();
            send(sender, "&a已追加区域：" + box.describe());
            return true;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("clear")) {
            plugin.arenaStore().root().set("arenas." + currentId() + "." + key, new ArrayList<>());
            plugin.arenaStore().save();
            send(sender, "&e已清空 " + key);
            return true;
        }
        if (args.length >= 3 && args[1].equalsIgnoreCase("remove")) {
            int index = Integer.parseInt(args[2]);
            if (index < 0 || index >= list.size()) {
                send(sender, "&c下标越界，共 " + list.size() + " 项");
                return true;
            }
            list.remove(index);
            plugin.arenaStore().root().set("arenas." + currentId() + "." + key, list);
            plugin.arenaStore().save();
            send(sender, "&a已移除第 " + index + " 项");
            return true;
        }
        send(sender, "&e共 " + list.size() + " 项；用法：/warwall setup " + action + " add|list|remove <i>|clear");
        return true;
    }

    private Box currentBox(CommandSender sender) {
        if (pos1 == null || pos2 == null) {
            send(sender, "&c先用 /warwall setup pos1 与 pos2 选两个角。");
            return null;
        }
        if (!pos1.getWorld().equals(pos2.getWorld())) {
            send(sender, "&c两个角不在同一个世界。");
            return null;
        }
        long volume = (long) (Math.abs(pos1.getBlockX() - pos2.getBlockX()) + 1)
                * (Math.abs(pos1.getBlockY() - pos2.getBlockY()) + 1)
                * (Math.abs(pos1.getBlockZ() - pos2.getBlockZ()) + 1);
        long max = plugin.config().wallMaxVolume();
        if (volume > max) {
            send(sender, "&c选区过大：" + volume + " 方块，上限 " + max);
            return null;
        }
        return Box.of(pos1.getWorld().getName(),
                pos1.getBlockX(), pos1.getBlockY(), pos1.getBlockZ(),
                pos2.getBlockX(), pos2.getBlockY(), pos2.getBlockZ());
    }

    private boolean writePointHere(CommandSender sender, String field) {
        return writePoint(sender, field, true);
    }

    /**
     * 把玩家当前位置（或指定字段）写入 arenas.<当前>.<field>。
     *
     * @param withRotation true 时同时写 yaw/pitch
     */
    private boolean writePoint(CommandSender sender, String field, boolean withRotation) {
        if (!(sender instanceof Player player)) {
            send(sender, "&c该子命令只能由玩家执行。");
            return true;
        }
        String path = field.startsWith("arenas.") || field.startsWith("border")
                ? (field.startsWith("arenas.") ? field : "arenas." + currentId() + "." + field)
                : "arenas." + currentId() + "." + field;
        ConfigurationSection section = plugin.arenaStore().root().createSection(path);
        section.set("x", round(player.getX()));
        section.set("y", round(player.getY()));
        section.set("z", round(player.getZ()));
        if (withRotation) {
            section.set("yaw", round(player.getYaw()));
            section.set("pitch", round(player.getPitch()));
        }
        plugin.arenaStore().save();
        send(sender, "&a已写入 " + path + " = " + player.getLocation().toVector());
        return true;
    }

    private boolean validate(CommandSender sender) {
        List<String> errors = new ArrayList<>();
        WarwallArena arena = plugin.arenaStore().read(currentId(), errors);
        if (arena == null) {
            errors.forEach(e -> send(sender, "&c" + e));
            return true;
        }
        errors.addAll(plugin.validateArena(arena));
        if (errors.isEmpty()) {
            send(sender, "&a校验通过，可以 /warwall setup save。");
        } else {
            send(sender, "&c发现 " + errors.size() + " 个问题：");
            errors.forEach(e -> send(sender, "&c - " + e));
        }
        return true;
    }

    // ===== debug =====

    private boolean handleDebug(CommandSender sender, String[] args) {
        if (args.length == 0) {
            send(sender, "&e/warwall debug arena <world> <x> <y> <z> | fast <on|off> | solo <on|off> | phase <STATE> | skip | energy <n> | wither <team> kill | end");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "fast" -> {
                boolean on = args.length < 2 || !"off".equalsIgnoreCase(args[1]);
                plugin.controller().setFastMode(on);
                send(sender, "&a快速模式：" + (on ? "开（各阶段 10 秒）" : "关"));
                return true;
            }
            case "solo" -> {
                boolean on = args.length < 2 || !"off".equalsIgnoreCase(args[1]);
                plugin.controller().setSoloMode(on);
                send(sender, "&a单人开局：" + (on ? "允许" : "不允许"));
                return true;
            }
            case "phase" -> {
                if (args.length < 2) {
                    send(sender, "&c用法：/warwall debug phase <" + Arrays.toString(MatchState.values()) + ">");
                    return true;
                }
                MatchState state = MatchState.parse(args[1]);
                plugin.controller().forcePhase(state);
                send(sender, "&a已切到阶段 " + state.name());
                return true;
            }
            case "skip" -> {
                plugin.controller().skipPhase();
                send(sender, "&a已跳过当前阶段计时。");
                return true;
            }
            case "energy" -> {
                if (!(sender instanceof Player player) || args.length < 2) {
                    send(sender, "&c用法：/warwall debug energy <0-100>（玩家执行）");
                    return true;
                }
                plugin.controller().setEnergy(player, Integer.parseInt(args[1]));
                send(sender, "&a能量已设置。");
                return true;
            }
            case "wither" -> {
                if (args.length < 3) {
                    send(sender, "&c用法：/warwall debug wither <team> kill");
                    return true;
                }
                TeamColor team = TeamColor.parse(args[1]);
                if (team == null) {
                    send(sender, "&c未知队伍：" + args[1]);
                    return true;
                }
                plugin.controller().killWither(team);
                send(sender, "&a已摧毁 " + team.chineseName() + " 的凋零。");
                return true;
            }
            case "end" -> {
                plugin.controller().forcePhase(MatchState.ENDED);
                send(sender, "&a已直接进入结算。");
                return true;
            }
            case "arena" -> {
                return plugin.debugBuildArena(sender, args);
            }
            default -> {
                send(sender, "&c未知 debug 子命令：" + args[0]);
                return true;
            }
        }
    }

    // ===== 权限与工具 =====

    private boolean requireAdmin(CommandSender sender) {
        if (sender.hasPermission("warwall.admin")) {
            return true;
        }
        send(sender, plugin.config().message("no-permission", "&c你没有执行该命令的权限。"));
        return false;
    }

    private boolean requireSetup(CommandSender sender) {
        if (sender.hasPermission("warwall.setup") || sender.hasPermission("warwall.admin")) {
            return true;
        }
        send(sender, plugin.config().message("no-permission", "&c你没有执行该命令的权限。"));
        return false;
    }

    private String currentId() {
        return plugin.selectedArenaId() == null ? "" : plugin.selectedArenaId();
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private void send(CommandSender sender, String message) {
        sender.sendMessage(plugin.config().prefix() + cn.infinite.warwall.config.WarwallConfig.colorize(message));
    }

    private void help(CommandSender sender) {
        send(sender, "&e超级战墙 —— 命令列表");
        send(sender, "&7/warwall join|leave|kit <职业>|status  玩家命令");
        send(sender, "&7/warwall start|stop|reload            管理命令");
        send(sender, "&7/warwall setup ...                    竞技场选区");
        send(sender, "&7/warwall debug ...                    调试");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("help", "join", "leave", "kit", "status", "start", "stop", "reload", "setup", "debug"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("setup")) {
            return filter(List.of("create", "select", "pos1", "pos2", "clear", "lobby", "spectator", "center",
                    "team", "border", "wall", "unbreakable", "mining", "nobuild", "validate", "save"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("debug")) {
            return filter(List.of("arena", "fast", "solo", "phase", "skip", "energy", "wither", "end"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("setup") && args[1].equalsIgnoreCase("team")) {
            return filter(List.of("red", "yellow", "green", "blue"), args[2]);
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("setup") && args[1].equalsIgnoreCase("team")) {
            return filter(List.of("spawn", "respawn", "wither"), args[3]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.startsWith(lower)) {
                result.add(option);
            }
        }
        return result;
    }
}
