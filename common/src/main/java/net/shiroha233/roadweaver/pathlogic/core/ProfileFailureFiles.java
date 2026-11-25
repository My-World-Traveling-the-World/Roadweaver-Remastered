/* 文件职责：失败才写完整快照；诊断按实际世界目录隔离，不占AB成功样本配额。 */
package net.shiroha233.roadweaver.pathlogic.core;

import com.google.common.hash.Hashing;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.shiroha233.roadweaver.pathlogic.profile.ProfileFailureSnapshot;
import static net.shiroha233.roadweaver.pathlogic.core.Road.LOGGER;

/** 临时文件写完再改名，同一组完整输入按摘要去重；不会留下被误认成成功证据的半份JSON。 */
final class ProfileFailureFiles {
    private static final Gson JSON=new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private ProfileFailureFiles(){}

    static void record(ServerLevel level,ProfileFailureSnapshot snapshot) {
        var directory=level.getServer().getWorldPath(LevelResource.ROOT).resolve("roadweaver-diagnostics")
                .resolve("profile-failures").resolve(snapshot.dimension().replace(':','_').replace('/','_'));
        recordTo(directory,snapshot);
    }

    static void recordTo(Path directory,ProfileFailureSnapshot snapshot) {
        try {
            var file=write(directory,snapshot);
            LOGGER.warn("【纵断面失败快照已保存】 file={} stations={} conflict={} 可纯数学重放={}",
                    file,snapshot.terrain().length,snapshot.conflict(),snapshot.beforePhysicalSelection());
        } catch(IOException|RuntimeException failure) {
            // 磁盘错误只影响诊断，原拒绝仍原样上抛；失败与正常没有失败必须能区分。
            LOGGER.warn("【纵断面失败快照写入失败】 directory={} cause={}",directory,failure.toString());
        }
    }

    static Path write(Path directory,ProfileFailureSnapshot snapshot) throws IOException {
        String json=JSON.toJson(snapshot);
        var file=directory.resolve("profile-"+Hashing.sha256().hashString(json,StandardCharsets.UTF_8)+".json");
        Files.createDirectories(directory);
        if(Files.isRegularFile(file))return file;
        var temporary=Files.createTempFile(directory,"profile-",".json.tmp");
        try {
            Files.writeString(temporary,json,StandardCharsets.UTF_8);
            try {Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE);}
            catch(FileAlreadyExistsException sameSnapshot){/* 并发记录同一份输入时保留已完成文件。 */}
        } finally {Files.deleteIfExists(temporary);}
        return file;
    }

    static ProfileFailureSnapshot read(Path file) throws IOException {
        var value=com.google.gson.JsonParser.parseString(Files.readString(file,StandardCharsets.UTF_8)).getAsJsonObject();
        // Gson会把缺失的int变成0；重放预算不能被损坏文件静默改写。
        requireFields(value,ProfileFailureSnapshot.class);
        requireFields(value.getAsJsonObject("conflict"),ProfileFailureSnapshot.Conflict.class);
        return JSON.fromJson(value,ProfileFailureSnapshot.class);
    }

    private static void requireFields(com.google.gson.JsonObject value,Class<?> type) {
        for(var field:type.getRecordComponents())if(!value.has(field.getName())||value.get(field.getName()).isJsonNull())
            throw new IllegalArgumentException("纵断面诊断缺字段: "+field.getName());
    }
}
