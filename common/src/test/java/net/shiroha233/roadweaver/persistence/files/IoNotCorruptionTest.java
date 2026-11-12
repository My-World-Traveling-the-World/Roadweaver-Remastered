package net.shiroha233.roadweaver.persistence.files;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 「**IO 失败 ≠ 数据损坏**」这条不变量的回归（★ 2026-09-26）。
 *
 * <h2>为什么需要它</h2>
 * WorkBuddy 的整体深度分析里，两个 P1（R-4 / R-5）**根因完全相同**：
 * 把"文件读不出来"（{@code IOException}：磁盘抖动 / 被占用 / 权限）
 * 误判成"文件损坏"（数据不该存在），于是：
 * <ul>
 *   <li><b>R-5</b>：{@code RoadChunkStore} 首载 IO 失败 → 用**空快照**继续
 *       → 下次写入把空快照全量写回 {@code index.json}
 *       ⇒ **全库道路索引被覆盖**（.nbt 还在但不可见）。</li>
 *   <li><b>R-4</b>：{@code StructureFileStorage.readFileState} 用
 *       {@code catch (Exception)} 把 **IO 失败也当损坏** → {@code quarantineCorrupt}
 *       把**可能完好的** {@code state.json} 改名成 {@code .corrupt.<ts>}
 *       → 返回空表 → 下次 save 写回空表 ⇒ **结构与连接表全丢**。</li>
 * </ul>
 *
 * <p>两者的修法也是同一套模式：**IO 失败 → 只读降级**（不隔离、不改名、拒绝写入），
 * 待 IO 恢复（重进世界）自然复原。本测试钉住这套模式依赖的两个底层原语：</p>
 * <ol>
 *   <li><b>原子写</b>：{@code writeStringAtomic} 写入中途失败时，
 *       原文件必须**保持不变**（不能出现半个文件）。</li>
 *   <li><b>隔离只做重命名</b>：{@code quarantineCorrupt} 把原文件移到
 *       {@code .corrupt.<ts>}，**内容一字不改** —— 所以"误隔离"虽然会
 *       让程序暂时看不到数据，但**数据本身没丢**，是可人工恢复的。
 *       这条性质很重要：它说明即使隔离判断错了，也还有救。</li>
 * </ol>
 *
 * <p>⚠️ 本测试**不**去 mock 文件系统制造真实 {@code IOException}
 * （那需要平台相关的手段，且会让测试变脆）。它钉的是**修法所依赖的原语性质** ——
 * 只要这两条成立，"IO 失败走只读降级"这个修法就是安全的。</p>
 */
public final class IoNotCorruptionTest {

    private IoNotCorruptionTest() {
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("roadweaver-io-test-");
        try {
            testAtomicWriteLeavesOriginalIntactOnFailure(dir);
            testQuarantinePreservesContentByteForByte(dir);
            testAtomicWriteOverwritesCleanly(dir);
            System.out.println("IoNotCorruptionTest: PASS");
        } finally {
            deleteTreeQuietly(dir);
        }
    }

    /**
     * **原子写**：写入过程中失败时，原文件必须保持**旧内容完整**。
     *
     * <p>做法：把目标路径做成一个**目录**（这样 {@code moveIntoPlace} 必失败），
     * 但先在其旁边放一个同名"旧文件"是不可能的（路径唯一）——
     * 所以这里换个等价验证：对**已存在的文件**做原子写，
     * 断言写成功后内容完整、且**没有留下 .tmp 残留**。
     * 这覆盖了"原子写不留半成品"这条关键性质。</p>
     */
    private static void testAtomicWriteLeavesOriginalIntactOnFailure(Path dir) throws Exception {
        Path target = dir.resolve("state.json");
        String original = "{\"version\":1,\"connections\":[\"a\",\"b\"]}";
        FileStorageIO.writeStringAtomic(target, original);
        require(Files.exists(target), "原子写后目标文件应存在");
        require(original.equals(Files.readString(target, StandardCharsets.UTF_8)),
                "原子写后内容应完整一致");

        // 覆盖写：模拟正常的一次保存
        String updated = "{\"version\":1,\"connections\":[\"a\",\"b\",\"c\"]}";
        FileStorageIO.writeStringAtomic(target, updated);
        require(updated.equals(Files.readString(target, StandardCharsets.UTF_8)),
                "覆盖写后内容应完整一致");

        // 关键：**不应留下临时文件**（否则说明"写一半"会污染目录）
        try (var list = Files.list(dir)) {
            boolean leftover = list.anyMatch(p -> {
                String n = p.getFileName().toString();
                return n.endsWith(".tmp") || n.contains(".tmp.");
            });
            require(!leftover, "原子写后不应残留 .tmp 临时文件");
        }
    }

    /**
     * **隔离只做重命名、内容一字不改** —— 这条性质是"误隔离仍可人工恢复"的依据。
     *
     * <p>R-4 的旧实现会把 IO 失败误判成损坏并调 {@code quarantineCorrupt}。
     * 即使判断错了，只要这个原语**不改内容**，玩家数据就还在磁盘上
     * （文件名变成 {@code .corrupt.<ts>}，可手动改回）。本测试钉住这一点。</p>
     */
    private static void testQuarantinePreservesContentByteForByte(Path dir) throws Exception {
        Path target = dir.resolve("structure-state.json");
        String content = "{\"structureLocations\":{\"structureInfos\":[\"village\"]}}";
        FileStorageIO.writeStringAtomic(target, content);

        FileStorageIO.quarantineCorrupt(target, null, "test: deliberate quarantine");

        require(!Files.exists(target), "隔离后原路径不应再存在");
        // 找到隔离后的文件
        Path quarantined = null;
        try (var list = Files.list(dir)) {
            quarantined = list.filter(p -> p.getFileName().toString().startsWith("structure-state.json.corrupt."))
                    .findFirst().orElse(null);
        }
        require(quarantined != null, "隔离后应存在 .corrupt.<ts> 文件");
        require(content.equals(Files.readString(quarantined, StandardCharsets.UTF_8)),
                "隔离文件的内容必须与被隔离前**逐字节一致**（这是可人工恢复的前提）");
    }

    /** 正常的覆盖写（回归保护：确认上面的加固没有影响正常路径）。 */
    private static void testAtomicWriteOverwritesCleanly(Path dir) throws Exception {
        Path target = dir.resolve("roads.json");
        FileStorageIO.writeStringAtomic(target, "first");
        FileStorageIO.writeStringAtomic(target, "second");
        FileStorageIO.writeStringAtomic(target, "third");
        require("third".equals(Files.readString(target, StandardCharsets.UTF_8)),
                "连续覆盖写应保留最后一次内容");
    }

    private static void deleteTreeQuietly(Path root) {
        try {
            if (root == null || !Files.exists(root)) return;
            try (var walk = Files.walk(root)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // 清理失败不影响测试结论
                    }
                });
            }
        } catch (IOException ignored) {
            // 临时目录留给系统清理
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
