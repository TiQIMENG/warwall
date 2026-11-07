package cn.infinite.warwall.world.io;

import cn.infinite.warwall.world.room.RoomSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

public final class PresetStore {
    private final Path directory;
    public PresetStore(Path directory) { this.directory = directory.toAbsolutePath().normalize(); }
    public void save(UUID player, RoomSettings settings) throws IOException { PropertyFiles.write(file(player), settings.toProperties()); }
    public Optional<RoomSettings> load(UUID player) throws IOException {
        Path file = file(player);
        if (!Files.exists(file)) return Optional.empty();
        try { return Optional.of(RoomSettings.fromProperties(PropertyFiles.read(file))); }
        catch (RuntimeException exception) { throw new IOException("预设内容无效。", exception); }
    }
    private Path file(UUID uuid) { return directory.resolve(uuid + ".properties"); }
}

