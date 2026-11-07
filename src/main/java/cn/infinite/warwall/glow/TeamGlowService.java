package cn.infinite.warwall.glow;

import cn.infinite.warwall.WarwallPlugin;
import cn.infinite.warwall.team.TeamColor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * 队友发光：让同队玩家之间隔着方块也能看到对方的发光轮廓。
 *
 * <p><b>为什么必须走反射</b>：Paper 1.21.11 的 API 只有全局 {@code Entity#setGlowing(boolean)}，
 * 它会对所有玩家生效，做不到「只让队友看见」。要按观看者分别控制，只能自己构造
 * {@code ClientboundSetEntityDataPacket} 单独发给指定玩家——这正是参考实现
 * `ShiinaYumeka/wallwarplugins` 的 `TeamMapGlowManager` 做的事，而它的 README 记录了一次
 * 线上事故（`NoSuchElementException: packet_handler`），所以这里的原则是：
 * <b>初始化任何一步失败就整体禁用，绝不半残运行</b>。
 *
 * <p>实现路径（Mojang 映射，1.21.11）：
 * <ol>
 *   <li>取 {@code ServerPlayer.entityData}（SynchedEntityData）</li>
 *   <li>读/写 shared flags 字节里的 0x40 位（发光）</li>
 *   <li>用 {@code packDirty()} 拿到这份脏数据，随后立刻把标志位改回原值</li>
 *   <li>构造 {@code ClientboundSetEntityDataPacket(entityId, dirty)} 只发给目标观看者</li>
 * </ol>
 */
public final class TeamGlowService {

    private final WarwallPlugin plugin;

    private boolean available;
    private String failureReason = "";

    // 反射句柄，init() 里一次性解析
    private Method craftPlayerGetHandle;
    private Method entityGetEntityData;
    private Method dataGet;
    private Method dataSet;
    private Method dataPackDirty;
    private Object sharedFlagsAccessor;
    private Class<?> packetClass;
    private java.lang.reflect.Constructor<?> packetConstructor;
    private Field connectionField;
    private Method sendPacket;
    private Method entityGetId;

    private BukkitTask task;
    /** 当前已经发光的目标 → 看到它的玩家集合，用于增量撤销。 */
    private final Map<UUID, Boolean> applied = new HashMap<>();

    public TeamGlowService(WarwallPlugin plugin) {
        this.plugin = plugin;
    }

    /** 初始化反射句柄；返回 false 表示本功能不可用。 */
    public boolean init() {
        try {
            Class<?> craftPlayer = Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer");
            Class<?> serverPlayer = Class.forName("net.minecraft.server.level.ServerPlayer");
            Class<?> entity = Class.forName("net.minecraft.world.entity.Entity");
            Class<?> entityData = Class.forName("net.minecraft.network.syncher.SynchedEntityData");
            Class<?> accessor = Class.forName("net.minecraft.network.syncher.EntityDataAccessor");
            packetClass = Class.forName(
                    "net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket");
            Class<?> packet = Class.forName("net.minecraft.network.protocol.Packet");
            Class<?> connection = Class.forName(
                    "net.minecraft.server.network.ServerGamePacketListenerImpl");

            craftPlayerGetHandle = craftPlayer.getMethod("getHandle");
            entityGetEntityData = entity.getMethod("getEntityData");
            entityGetId = entity.getMethod("getId");
            dataGet = entityData.getMethod("get", accessor);
            dataSet = entityData.getMethod("set", accessor, Object.class, boolean.class);

            // packDirty 在不同版本里可能改名，按「无参 + 返回 List」扫描更稳。
            for (Method method : entityData.getMethods()) {
                if (method.getParameterCount() == 0
                        && java.util.List.class.isAssignableFrom(method.getReturnType())) {
                    dataPackDirty = method;
                    break;
                }
            }
            if (dataPackDirty == null) {
                throw new NoSuchMethodException("SynchedEntityData 上没有返回 List 的无参方法");
            }

            // 共享标志位 accessor 是 private 静态字段，而且不同版本名字可能变；
            // 按「类型是 EntityDataAccessor + 名字里有 FLAGS」扫描，比硬编码安全。
            sharedFlagsAccessor = null;
            for (Field field : Class.forName("net.minecraft.world.entity.Entity").getDeclaredFields()) {
                if (!field.getType().getName().endsWith("EntityDataAccessor")) {
                    continue;
                }
                field.setAccessible(true);
                Object value = field.get(null);
                if (value != null && field.getName().toUpperCase(java.util.Locale.ROOT).contains("FLAG")) {
                    sharedFlagsAccessor = value;
                    break;
                }
            }
            if (sharedFlagsAccessor == null) {
                throw new NoSuchFieldException("找不到 Entity 的共享标志 accessor");
            }

            connectionField = serverPlayer.getField("connection");
            sendPacket = connection.getMethod("send", packet);

            // 包构造器按 (int, List) 找，不写死参数类型。
            packetConstructor = null;
            for (java.lang.reflect.Constructor<?> candidate : packetClass.getConstructors()) {
                Class<?>[] params = candidate.getParameterTypes();
                if (params.length == 2 && params[0] == int.class
                        && java.util.List.class.isAssignableFrom(params[1])) {
                    packetConstructor = candidate;
                    break;
                }
            }
            if (packetConstructor == null) {
                throw new NoSuchMethodException("找不到 (int, List) 的实体元数据包构造器");
            }

            available = true;
            plugin.getLogger().info("队友发光已启用（按观看者下发发光标志）");
            return true;
        } catch (Throwable ex) {
            available = false;
            failureReason = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            plugin.getLogger().warning("队友发光不可用，已自动禁用。原因：" + failureReason);
            return false;
        }
    }

    public boolean available() {
        return available;
    }

    public String failureReason() {
        return failureReason;
    }

    /** 启动周期刷新（每 40 tick，够用且不浪费带宽）。 */
    public void start() {
        if (!available || task != null) {
            return;
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, 40L, 40L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        applied.clear();
    }

    /** 全量刷新一次：队友置发光，敌人清发光。 */
    public void refresh() {
        if (!available || !plugin.controller().isRunning()) {
            return;
        }
        for (Player viewer : plugin.teams().participants()) {
            TeamColor viewerTeam = plugin.teams().teamOf(viewer);
            if (viewerTeam == null) {
                continue;
            }
            for (Player target : plugin.teams().participants()) {
                boolean sameTeam = plugin.teams().teamOf(target) == viewerTeam;
                setGlowFor(viewer, target, sameTeam);
            }
        }
    }

    /**
     * 只对某个观看者设置某个目标的发光状态。
     *
     * @param glowing true = 让 viewer 看到 target 发光
     */
    public void setGlowFor(Player viewer, Player target, boolean glowing) {
        if (!available || viewer.equals(target)) {
            return;
        }
        try {
            Object targetHandle = craftPlayerGetHandle.invoke(target);
            Object data = entityGetEntityData.invoke(targetHandle);
            byte flags = (byte) dataGet.invoke(data, sharedFlagsAccessor);
            boolean currently = (flags & 0x40) != 0;
            if (currently == glowing) {
                return;
            }
            byte updated = (byte) (glowing ? (flags | 0x40) : (flags & ~0x40));
            dataSet.invoke(data, sharedFlagsAccessor, updated, true);
            Object dirty = dataPackDirty.invoke(data);
            // 立刻改回，保证服务端自身状态不被我们污染
            dataSet.invoke(data, sharedFlagsAccessor, flags, false);

            int entityId = (int) entityGetId.invoke(targetHandle);
            Object packet = packetConstructor.newInstance(entityId, dirty);

            Object viewerHandle = craftPlayerGetHandle.invoke(viewer);
            Object connection = connectionField.get(viewerHandle);
            sendPacket.invoke(connection, packet);
        } catch (Throwable ex) {
            // 单次失败只降级，不把异常抛进事件循环
            plugin.getLogger().warning("队友发光下发失败，已禁用该功能：" + ex.getMessage());
            available = false;
            stop();
        }
    }
}
