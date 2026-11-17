/* 文件职责：复现统计戳相同而文件内容已变；这是现存盲点探针，不是证明已修复的守卫。 */
package net.shiroha233.roadweaver.persistence.chunk;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
public final class ShardStampProbe {
    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory("roadweaver-stamp-probe"),chunks=Files.createDirectory(root.resolve("chunks"));
        Path first=chunks.resolve("0_0.json"),second=chunks.resolve("1_0.json");
        try {
            Files.writeString(first,"{\"roads\":[1]}");Files.writeString(second,"{\"roads\":[2]}");
            Files.setLastModifiedTime(first,FileTime.fromMillis(1000));Files.setLastModifiedTime(second,FileTime.fromMillis(3000));
            var store=new RoadChunkStore(root);
            var compute=RoadChunkStore.class.getDeclaredMethod("computeShardStamp",Path.class);compute.setAccessible(true);
            Object before=compute.invoke(store,chunks);String original=Files.readString(first);
            // 不恢复修改时间：只把非最新文件改成另一份同长度内容，最大mtime依然由另一文件持有。
            Files.writeString(first,"{\"roads\":[9]}");Files.setLastModifiedTime(first,FileTime.fromMillis(2000));
            Object after=compute.invoke(store,chunks);
            var matches=before.getClass().getDeclaredMethod("matches",before.getClass());matches.setAccessible(true);
            boolean accepted=(boolean)matches.invoke(before,after);
            if(original.equals(Files.readString(first)) || !accepted)throw new AssertionError("现存统计戳盲点不再复现，需更新探针");
            System.out.println("STAMP_BLIND_SPOT contentChanged=true maxMtimeUnchanged=true aggregateAccepted=true");
        } finally {
            // 只清理本探针明确创建的四个路径，不递归遍历用户目录。
            Files.deleteIfExists(first);Files.deleteIfExists(second);Files.deleteIfExists(chunks);Files.deleteIfExists(root);
        }
    }
}
