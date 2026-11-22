/* 文件职责：给联机玩家发布随服务端存档备份的周目标识。 */
package net.shiroha233.roadweaver.map;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/** 私人笔记按实际存档和玩家归属；联机客户端只需要知道周目标识。 */
public final class MapNotesScope {
    private MapNotesScope() {}

    public static UUID worldId(Path save) throws IOException {
        // 服务端加入回调串行执行。标识只在首次联机时产生；备份还原沿用，空白新档重建。
        Path file = save.resolve("data/roadweaver/map-world-id");
        if (Files.notExists(file)) {
            UUID id = UUID.randomUUID();
            Files.createDirectories(file.getParent());
            Files.writeString(file, id.toString(), StandardOpenOption.CREATE_NEW);
            return id;
        }
        return UUID.fromString(Files.readString(file).trim());
    }
}
