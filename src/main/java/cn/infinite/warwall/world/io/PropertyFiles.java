package cn.infinite.warwall.world.io;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.UUID;

public final class PropertyFiles {
    private PropertyFiles() { }
    public static Properties read(Path path) throws IOException {
        rejectLinks(path);
        Properties values = new Properties();
        if (Files.exists(path)) {
            if (Files.isSymbolicLink(path)) throw new IOException("拒绝读取符号链接。");
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) { values.load(reader); }
        }
        return values;
    }
    public static void write(Path path, Properties values) throws IOException {
        rejectLinks(path);
        Files.createDirectories(path.getParent());
        if (Files.isSymbolicLink(path) || Files.isSymbolicLink(path.getParent())) throw new IOException("拒绝写入符号链接。");
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) { values.store(writer, "WarwallTool"); }
            try { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
    private static void rejectLinks(Path path) throws IOException {
        Path current = path.toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isSymbolicLink(current)) throw new IOException("文件路径包含符号链接。");
            current = current.getParent();
        }
    }
}

