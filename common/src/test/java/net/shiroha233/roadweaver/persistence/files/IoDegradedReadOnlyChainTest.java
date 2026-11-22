package net.shiroha233.roadweaver.persistence.files;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * **「IO 失败 ⇒ 只读降级」行为链的回归**（★ 2026-09-26 修 WorkBuddy I-3）。
 *
 * <h2>要防什么</h2>
 * 0.8.396 的修法是：读文件遇 {@code IOException} 时置 {@code degradedReadOnly}
 * ⇒ {@code save()} 拒绝写入（不拿空数据覆盖真数据）⇒ 会话结束后复位。
 *
 * <p>{@code IoNotCorruptionTest} 已钉住了**两个底层原语**
 * （原子写不留残留、quarantine 内容不变），但**这条行为链本身没测** ——
 * 若有人把 {@code catch (Exception)} 写回去、或删掉复位逻辑，测试挡不住。</p>
 *
 * <h2>本测试怎么测（以及为什么这样测）</h2>
 * ⚠️ 真实的 {@code save(ServerLevel, StateData)} 需要 {@code ServerLevel} 实例，
 * **离线环境无法构造**（不像纯函数那样可以随便调）。而 mock 整个 MC 服
 * 既脆弱又与项目既有的"不 mock"风格不符。
 *
 * <p>所以本测试改为**结构性断言 + 反射状态检查**，覆盖三个关键性质：</p>
 * <ol>
 *   <li><b>字段存在且是 volatile</b> —— 降级标志要跨线程可见
 *       （客户端/服务端线程都可能读）。</li>
 *   <li><b>写入路径确实检查了它</b> —— 读 {@code save()} 源码，断言出现
 *       {@code degradedReadOnly} 且是**抛异常**（而不是静默 return，
 *       否则调用方会误以为写成功）。</li>
 *   <li><b>有复位通道</b> —— {@code resetSnapshotCache()} 里必须清掉它，
 *       否则一次瞬时抖动会让该会话**再也无法保存**。</li>
 * </ol>
 *
 * <p>这三条正好对应 WorkBuddy 说的「置位 ⇒ save 拒绝 ⇒ 复位可写」链条 ——
 * 虽然不能真跑一遍文件 IO，但**结构一旦被改坏立刻会红**。</p>
 */
public final class IoDegradedReadOnlyChainTest {

    private IoDegradedReadOnlyChainTest() {
    }

    private static final String STORAGE_SRC =
            "common/src/main/java/net/shiroha233/roadweaver/persistence/files/StructureFileStorage.java";
    private static final String CHUNK_SRC =
            "common/src/main/java/net/shiroha233/roadweaver/persistence/chunk/RoadChunkStore.java";

    private static final String FLAG = "degradedReadOnly";
    private static final String CHUNK_FLAG = "readOnlyDegraded";

    public static void main(String[] args) throws Exception {
        testFlagExistsAndIsVolatile();
        testWritePathRejectsWhileDegraded();
        testResetPathClearsFlag();
        testSuccessfulReadClearsFlag();     // ★ 修 W-8/I-5 的"读成功自动解除"
        testChunkStoreHasSymmetricGuard();
        System.out.println("IoDegradedReadOnlyChainTest: PASS");
    }

    /** ① 降级标志存在且 volatile（跨线程可见）。 */
    private static void testFlagExistsAndIsVolatile() throws Exception {
        checkFlag(StructureFileStorage.class, FLAG);
        checkFlag(Class.forName("net.shiroha233.roadweaver.persistence.chunk.RoadChunkStore"),
                CHUNK_FLAG);
    }

    private static void checkFlag(Class<?> owner, String name) throws Exception {
        Field f = null;
        for (Field candidate : owner.getDeclaredFields()) {
            if (candidate.getName().equals(name)) {
                f = candidate;
                break;
            }
        }
        require(f != null, owner.getSimpleName() + " 里找不到 " + name
                + " —— 只读降级机制被移除了？");
        require(java.lang.reflect.Modifier.isVolatile(f.getModifiers()),
                name + " 必须是 volatile —— 它会被多个线程读写"
                        + "（服务端 tick / 客户端渲染都可能碰）");
        require(f.getType() == boolean.class, name + " 应是 boolean");
    }

    /** ② 写入路径在降级时**抛异常**拒绝（而不是静默 return）。 */
    private static void testWritePathRejectsWhileDegraded() throws Exception {
        String saveBody = readMethodBody(STORAGE_SRC, "private static void save(");
        require(saveBody != null, "找不到 StructureFileStorage.save(...)");
        require(saveBody.contains(FLAG),
                "save() 没有检查 " + FLAG + " —— 只读降级时会把空数据写回，"
                        + "覆盖磁盘上可能完好的真实数据（这正是 W-4/R-4 的根因）");
        require(saveBody.contains("throw "),
                "save() 在降级时应**抛异常**而不是静默 return ——"
                        + "否则调用方误以为保存成功");
    }

    /** ③ 有复位通道（否则一次瞬时抖动会让整个会话写不进去）。 */
    private static void testResetPathClearsFlag() throws Exception {
        String resetBody = readMethodBody(STORAGE_SRC, "public static synchronized void resetSnapshotCache(");
        require(resetBody != null, "找不到 resetSnapshotCache()");
        require(resetBody.contains(FLAG),
                "resetSnapshotCache() 没有清 " + FLAG + " —— "
                        + "一次瞬时 IO 抖动会让该会话**再也无法保存**");
    }

    /** ④ 读成功时自动解除（★ W-8/I-5）。 */
    private static void testSuccessfulReadClearsFlag() throws Exception {
        String readBody = readMethodBody(STORAGE_SRC, "private static StateData readFileState(");
        require(readBody != null, "找不到 readFileState()");
        require(readBody.contains(FLAG),
                "readFileState() 里没有对 " + FLAG + " 的处理 —— "
                        + "应在**读成功**时自动解除降级（「能读就能写」），"
                        + "否则瞬时抖动会导致会话级只读（W-8/I-5）");
    }

    /** ⑤ RoadChunkStore 有对称的守卫（同一个缺陷类别，两处都要有）。 */
    private static void testChunkStoreHasSymmetricGuard() throws Exception {
        // 检查真正写入的入口，避免把存储门面的提前返回误认为降级保护。
        String publishBody = readMethodBody(CHUNK_SRC,
                "private boolean publish(RoadSnapshot previous, RoadSnapshot next, long nextGeneration,");
        require(publishBody != null,
                "找不到 RoadChunkStore.publish(...) 的 4 参数入口（真正的写入闸门所在）");
        require(publishBody.contains(CHUNK_FLAG),
                "RoadChunkStore.publish() 没有检查 " + CHUNK_FLAG
                        + " —— 与 StructureFileStorage 是同一类缺陷，必须对称防守");
    }

    // ─────────────────── 源码读取 ───────────────────

    /** 读方法体：从含 signature 片段的行为起点，到配对花括号结束。 */
    private static String readMethodBody(String relativePath, String signatureFragment) throws Exception {
        Path path = locate(relativePath);
        if (path == null) {
            return null;
        }
        var lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            String t = lines.get(i).trim();
            if (t.startsWith("*") || t.startsWith("//")) {
                continue;
            }
            if (t.contains(signatureFragment)) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        boolean seenBrace = false;
        for (int i = start; i < lines.size(); i++) {
            String line = lines.get(i);
            sb.append(line).append('\n');
            for (char c : line.toCharArray()) {
                if (c == '{') {
                    depth++;
                    seenBrace = true;
                } else if (c == '}') {
                    depth--;
                }
            }
            if (seenBrace && depth <= 0) {
                break;
            }
        }
        return sb.toString();
    }

    private static Path locate(String relativePath) {
        Path cwd = Path.of("").toAbsolutePath();
        Path[] candidates = {
                cwd.resolve(relativePath),
                cwd.resolve("..").resolve(relativePath),
                cwd.resolve("..").resolve("..").resolve(relativePath),
        };
        for (Path p : candidates) {
            if (Files.isRegularFile(p)) {
                return p.normalize();
            }
        }
        return null;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
