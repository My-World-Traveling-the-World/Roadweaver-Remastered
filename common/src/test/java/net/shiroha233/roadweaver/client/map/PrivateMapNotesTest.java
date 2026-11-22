package net.shiroha233.roadweaver.client.map;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import net.shiroha233.roadweaver.client.map.data.MapDataStorage;
import net.shiroha233.roadweaver.map.MapNotesScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

final class PrivateMapNotesTest {
    @TempDir Path root;
    private final UUID player = UUID.randomUUID();
    private static final String POINT = "minecraft:overworld|10,64,20";

    @Test void sameNamedSavesAndPlayersCannotReadEachOthersNotes() {
        // 两个路径的末段与坐标都一样，只有真实存档目录不同。
        Path a = MapDataStorage.local(root.resolve("a/world"), player);
        Path b = MapDataStorage.local(root.resolve("b/world"), player);
        write(a, "甲档");
        assertTrue(MapDataStorage.loadNotes(b).notes.isEmpty());
        write(b, "乙档");
        assertEquals(List.of("甲档"), MapDataStorage.loadNotes(a).notes.get(POINT));
        assertEquals(List.of("乙档"), MapDataStorage.loadNotes(b).notes.get(POINT));
        assertTrue(MapDataStorage.loadNotes(MapDataStorage.local(root.resolve("a/world"),
                UUID.randomUUID())).aliases.isEmpty());
    }

    @Test void sameServerNewWorldIsBlankAndRestoredWorldRetainsPrivateNotes() throws Exception {
        Path save = root.resolve("server/a");
        var first = MapNotesScope.worldId(save);
        Path local = MapDataStorage.remote(root, "example:25565", first, player);
        write(local, "第一周目");
        var second = MapNotesScope.worldId(root.resolve("server/b"));
        assertTrue(MapDataStorage.loadNotes(MapDataStorage.remote(root, "example:25565", second, player)).notes.isEmpty());
        assertTrue(MapDataStorage.loadNotes(MapDataStorage.remote(root, "example:25565", first, UUID.randomUUID())).aliases.isEmpty());
        // 把服务端标识文件随备份还原到另一目录；玩家本机不动，原周目仍应找到原笔记。
        Path restored = root.resolve("server/restored");
        Path relative = Path.of("data/roadweaver/map-world-id");
        Files.createDirectories(restored.resolve(relative).getParent());
        Files.copy(save.resolve(relative), restored.resolve(relative));
        var reopened = MapDataStorage.remote(root, "example:25565", MapNotesScope.worldId(restored), player);
        assertEquals("第一周目", MapDataStorage.loadNotes(reopened).aliases.get(POINT));
        assertEquals(List.of("第一周目"), MapDataStorage.loadNotes(reopened).notes.get(POINT));
    }

    private static void write(Path dir, String text) {
        var data = new MapDataStorage.NotesData();
        data.notes.put(POINT, List.of(text));
        data.aliases.put(POINT, text);
        MapDataStorage.saveNotes(dir, data);
    }
}
