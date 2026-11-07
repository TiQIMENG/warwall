package cn.infinite.warwall.world;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 把内置的 wallwar 数据包释放到随机地图世界的 {@code datapacks/} 里。
 *
 * <p>为什么要这一步：照搬过来的 `MapPreparation` 在评分时会读数据包的原创遗迹模板与
 * 奖励表，缺包就直接判「原始遗迹模板或奖励表加载失败」。让管理员手工放包容易漏，
 * 所以把数据包压成 0.95 MB 的 zip 随插件分发，启动时自动释放。
 *
 * <p>只做一次：目标文件存在就跳过，避免每次启动都覆盖管理员自己改过的版本。
 */
public final class DatapackInstaller {

    /** 内置资源名，对应 {@code src/main/resources/wallwar-datapack.zip}。 */
    private static final String RESOURCE = "wallwar-datapack.zip";
    /** 释放后的文件名；用 zip 形式 Minecraft 会自动识别。 */
    private static final String TARGET = "wallwar.zip";

    private DatapackInstaller() {
    }

    /**
     * 确保数据包已就位。
     *
     * @return true 表示目标位置已有数据包（新放的或原本就在）
     */
    public static boolean ensureInstalled(JavaPlugin plugin, World world) {
        if (world == null) {
            return false;
        }
        File datapacks = new File(world.getWorldFolder(), "datapacks");
        File target = new File(datapacks, TARGET);
        if (target.isFile()) {
            return true;
        }
        if (!datapacks.exists() && !datapacks.mkdirs()) {
            plugin.getLogger().warning("无法创建数据包目录：" + datapacks.getAbsolutePath());
            return false;
        }
        try (InputStream stream = plugin.getResource(RESOURCE)) {
            if (stream == null) {
                plugin.getLogger().warning("插件内缺少 " + RESOURCE + "，无法自动安装数据包");
                return false;
            }
            Files.copy(stream, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            plugin.getLogger().info("已释放内置战墙数据包到 " + target.getPath());
            return true;
        } catch (IOException ex) {
            plugin.getLogger().warning("释放数据包失败：" + ex.getMessage());
            return false;
        }
    }
}
