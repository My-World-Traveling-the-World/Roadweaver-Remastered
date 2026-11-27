package net.shiroha233.roadweaver.persistence.files;

import java.nio.file.Files;
import java.nio.file.Path;

/** 用真实文件故障串起生产读写入口；仅检查是否存在某个字段无法证明另一存档仍能写。 */
public final class IoDegradedReadOnlyChainTest {
    private IoDegradedReadOnlyChainTest() {}

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("rw-structure-recovery");
        Path first = root.resolve("a.json"), second = root.resolve("b.json");
        try {
            Files.createDirectory(first);
            var unread = StructureFileStorage.readFileState(first, false);
            var healthy = StructureFileStorage.readFileState(second, false);
            healthy.meta.put("sentinel", "world-b");
            var a = new net.minecraft.core.BlockPos(1, 64, 0);
            var b = new net.minecraft.core.BlockPos(16, 64, 0);
            var c = new net.minecraft.core.BlockPos(32, 70, 0);
            var auto = new net.shiroha233.roadweaver.core.model.StructureConnection(a,b);
            var reverse = new net.shiroha233.roadweaver.core.model.StructureConnection(b,a);
            var manual = new net.shiroha233.roadweaver.core.model.StructureConnection(b,c);
            healthy.connections.add(auto);
            var route = java.util.UUID.randomUUID();
            if (!StructureFileStorage.claimPlayerEdges(healthy, route, java.util.List.of(reverse, manual, manual))
                    .equals(java.util.List.of(manual))) throw new AssertionError("逆向自动路与重复分段不得占为己有");
            StructureFileStorage.writeFileState(second, healthy);
            var reloaded = StructureFileStorage.readFileState(second, false);
            if (!java.util.Set.of(StructureFileStorage.playerEdge(manual)).equals(StructureFileStorage.acceptedPlayerEdges(reloaded, route)))
                throw new AssertionError("重开后必须只恢复本次接纳的段");
            if (!"world-b".equals(reloaded.meta.get("sentinel")))
                throw new AssertionError("另一存档必须正常读写");
            Files.delete(first);
            if (!StructureFileStorage.readFileState(first, true).readFailed) throw new AssertionError("故障后的暂缺不能解除保护");
            Files.writeString(first, "{\"meta\":{\"sentinel\":\"world-a\"}}");
            var original = Files.readAllBytes(first);
            try {
                StructureFileStorage.writeFileState(first, unread);
                throw new AssertionError("不得用故障时的空表覆盖重新出现的文件");
            } catch (IllegalStateException expected) {
                if (!java.util.Arrays.equals(original, Files.readAllBytes(first))) throw new AssertionError("原字节被改写");
            }
            var restored = StructureFileStorage.readFileState(first, true);
            if (!"world-a".equals(restored.meta.get("sentinel"))) throw new AssertionError("未重读原文件");
            StructureFileStorage.writeFileState(first, restored);
        } finally {
            Files.deleteIfExists(first);
            Files.deleteIfExists(second);
            Files.deleteIfExists(root);
        }
        System.out.println("IoDegradedReadOnlyChainTest: PASS");
    }
}
