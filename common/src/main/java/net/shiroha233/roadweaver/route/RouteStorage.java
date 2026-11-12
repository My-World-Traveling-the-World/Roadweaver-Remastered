package net.shiroha233.roadweaver.route;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.persistence.files.FileStoragePathResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 玩家手绘路线的持久化。
 *
 * <h2>为什么必须持久化</h2>
 * 用户明确要求「跑图看一遍再调」—— 这意味着路线必须跨会话保留：
 * 玩家在地图上粗画 → 存档 → 跑图 → 回来接着调。
 *
 * <h2>存放位置</h2>
 * <pre>
 *   &lt;世界&gt;/data/roadweaver/persistence/routes/&lt;维度&gt;/routes.json
 * </pre>
 * 复用现有 {@link FileStoragePathResolver}，与道路数据、路牌文本等放在一起。
 *
 * <h2>为什么用单个 JSON 文件而不是分片</h2>
 * 路线数量是**玩家手画级别**（个位数到几十条），不是算法生成的成千上万条。
 * 单文件读写简单、原子替换容易做，也便于出问题时人肉查看。
 *
 * <h2>写入策略</h2>
 * 先写临时文件再原子重命名（{@code Files.move} + {@code ATOMIC_MOVE}），
 * 避免写到一半崩溃导致文件损坏、路线全丢。
 */
public final class RouteStorage {

    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver/route-storage");

    private static final String CATEGORY = "routes";
    private static final String FILE_NAME = "routes.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private RouteStorage() {
    }

    private static Path fileFor(ServerLevel level) {
        return FileStoragePathResolver.categoryRoot(level, CATEGORY).resolve(FILE_NAME);
    }

    // ───────────────────────── 读 ─────────────────────────

    /**
     * 读取该维度所有路线。
     *
     * <h2>异常策略（★ 2026-09-25：修正注释与实现不符之处）</h2>
     * 原先这里只写「任何异常都返回空表」—— 那句话**已与实现不一致**：
     * <ul>
     *   <li><b>顶层 JSON 解析失败</b>（整个文件坏了）→ 先把原文件改名
     *       {@code routes.json.corrupt-<时间戳>} **隔离保留**，再返回空表。
     *       不隔离的话，下一次编辑就会把空表写回，玩家路线**永久丢失**
     *       （审计 R3-P3-4 修的就是这个）。</li>
     *   <li><b>单条路线解析失败</b> → 跳过该条、其余保留（{@code resultOrPartial}）。</li>
     *   <li><b>读文件 IOException</b> / 列表结构异常 → 返回空表并记日志。</li>
     * </ul>
     * 无论哪条路径都**不抛异常**，绝不因为存档坏了把游戏搞崩。
     */
    public static Map<UUID, PlayerRoute> loadAll(ServerLevel level) {
        Map<UUID, PlayerRoute> out = new LinkedHashMap<>();
        if (level == null) {
            return out;
        }
        Path path = fileFor(level);
        if (!Files.exists(path)) {
            return out;
        }
        String text;
        try {
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("读取手绘路线失败，按空处理: {}", path, e);
            return out;
        }
        // 顶层解析单独 catch：只有「整个文件坏了」才需要隔离
        JsonElement root;
        try {
            root = JsonParser.parseString(text);
        } catch (RuntimeException e) {
            LOGGER.error("手绘路线文件损坏，已隔离原文件以避免被空表覆盖: {}", path, e);
            quarantineCorrupt(path);
            return out;
        }
        try {
            List<PlayerRoute> routes = decodeList(root);
            for (PlayerRoute r : routes) {
                if (r != null && r.id() != null) {
                    out.put(r.id(), r);
                }
            }
        } catch (RuntimeException e) {
            // 单条解析失败已在 decodeList 内跳过；到这里说明是结构性问题
            LOGGER.warn("解析手绘路线列表失败，按空处理: {}", path, e);
        }
        return out;
    }

    /**
     * 把损坏的文件改名保留（★ 审计 R3-P3-4）。
     *
     * <p>改名失败只记日志 —— 隔离是「尽力而为」的抢救手段，
     * 不能因为它失败而让读流程抛出去。</p>
     */
    private static void quarantineCorrupt(Path path) {
        try {
            Path quarantine = path.resolveSibling(path.getFileName()
                    + ".corrupt-" + System.currentTimeMillis());
            Files.move(path, quarantine,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            LOGGER.error("已把损坏的手绘路线文件保留为 {}（原文件内容未丢失，可人工抢救）",
                    quarantine);
        } catch (IOException e) {
            LOGGER.warn("隔离损坏的手绘路线文件失败（将保持原样）: {}", path, e);
        }
    }

    /**
     * 单条路线的最大控制点数（★ 审计 R3-P3-4 的 DoS 分支）。
     *
     * <p>手工编辑存档可以塞进任意多的点，而平滑/注入会按 N 展开 ——
     * 几十万个点足以让服务端主线程卡死。正常玩家手绘不可能到这个量级
     * （那需要点 4096 次），所以超限视为损坏数据、跳过该条。</p>
     */
    private static final int MAX_POINTS_PER_ROUTE = 4096;

    private static List<PlayerRoute> decodeList(JsonElement root) {
        // 期望格式：{"routes":[...]}；也兼容「直接就是数组」
        JsonElement array = root;
        if (root != null && root.isJsonObject() && root.getAsJsonObject().has("routes")) {
            array = root.getAsJsonObject().get("routes");
        }
        if (array == null || !array.isJsonArray()) {
            return List.of();
        }
        List<PlayerRoute> result = new ArrayList<>();
        Codec<PlayerRoute> codec = PlayerRoute.CODEC;
        for (JsonElement e : array.getAsJsonArray()) {
            codec.parse(JsonOps.INSTANCE, e)
                    .resultOrPartial(err -> LOGGER.warn("跳过一条解析失败的路线: {}", err))
                    .ifPresent(r -> {
                        // ★ 审计 R3-P3-4：点数上限校验，防手工编辑的存档卡死服务端
                        if (r != null && r.pointCount() > MAX_POINTS_PER_ROUTE) {
                            LOGGER.warn("跳过一条点数异常巨大的路线 id={} points={}（上限 {}）",
                                    r.id(), r.pointCount(), MAX_POINTS_PER_ROUTE);
                            return;
                        }
                        result.add(r);
                    });
        }
        return result;
    }

    // ───────────────────────── 写 ─────────────────────────

    /**
     * 覆盖写入该维度的全部路线。
     *
     * <h2>★ 审计 P1-8：必须把失败信号传出去</h2>
     * 原实现 catch 掉 {@code IOException} 只记日志、**不向外报告**，
     * 于是调用方 {@code RouteEditorSession.saveToWorld} 拿不到失败信号，
     * 照样把 {@code dirty = false} —— 磁盘写失败时**静默丢数据且永不重试**。
     * 改为返回 boolean：只有真正写盘成功才是 true。
     *
     * <h2>★ 审计 P2-1：写必须串行化</h2>
     * 这个方法会被**两个线程**调用：
     * <ul>
     *   <li>客户端 tick（{@code tickAutoSave}）</li>
     *   <li>服务端线程（{@code submitForBuild} 的 {@code server.execute} 内）</li>
     * </ul>
     * 两者写的是**同一个 .tmp 路径**。Windows 下并发写同一文件多半抛
     * {@code AccessDeniedException}（等于本次保存丢失），最坏情况两个
     * {@code Files.move} 交错。所以整个写流程加锁串行。
     *
     * @return true 表示确实写盘成功
     */
    public static boolean saveAll(ServerLevel level, Map<UUID, PlayerRoute> routes) {
        if (level == null || routes == null) {
            return false;
        }
        Path path = fileFor(level);
        // 先做快照再序列化：调用方的表可能在别的线程被改
        // （客户端遍历保存时服务端线程正在 update）。用 List.copyOf 拿到
        // 一份稳定视图，避免遍历途中被改。
        java.util.List<PlayerRoute> snapshot;
        try {
            snapshot = java.util.List.copyOf(routes.values());
        } catch (RuntimeException e) {
            LOGGER.warn("读取路线快照失败，跳过本次保存", e);
            return false;
        }

        // 串行化：与同维度的其他写入互斥（见方法注释 P2-1）
        synchronized (writeLock(level)) {
            try {
                Files.createDirectories(path.getParent());

                var arr = new com.google.gson.JsonArray();
                Codec<PlayerRoute> codec = PlayerRoute.CODEC;
                for (PlayerRoute r : snapshot) {
                    codec.encodeStart(JsonOps.INSTANCE, r)
                            .resultOrPartial(err -> LOGGER.warn("序列化路线失败，跳过: {}", err))
                            .ifPresent(arr::add);
                }
                var root = new com.google.gson.JsonObject();
                root.add("routes", arr);
                root.addProperty("version", 1);

                // ★ 审计 R3-P3-4：先把**上一版**内容备份成 .bak，再覆盖写入。
                // 配合 loadAll 的 .corrupt 隔离，形成「损坏也能救回来」的双保险。
                backupPrevious(path);
                writeAtomic(path, GSON.toJson(root));
                return true;
            } catch (IOException | RuntimeException e) {
                LOGGER.error("保存手绘路线失败: {}", path, e);
                return false;
            }
        }
    }

    /**
     * 每个维度一把写锁。
     *
     * <p>用 {@code WeakHashMap} 而不是全局锁：不同维度的写互不影响，
     * 而且 ServerLevel 回收后锁对象也跟着回收，不泄漏。</p>
     */
    private static final Map<ServerLevel, Object> WRITE_LOCKS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private static Object writeLock(ServerLevel level) {
        return WRITE_LOCKS.computeIfAbsent(level, k -> new Object());
    }

    // ★ 审计 R3-P3-2：`put(level, route)` / `remove(level, id)` 已删除。
    //
    // 两者都是「loadAll → 改 → saveAll」的包装，而唯一调用方
    // （RouteEditorSession）本来就在内存里持有完整的 routes 表，
    // 直接 saveAll 即可 —— 再 loadAll 一次是多余的磁盘往返，
    // 而且 put/remove 各自 loadAll 会让「并发编辑 + 单条写」出现 lost update。
    // 全仓已无调用者，故删除以消除维护噪音。

    /**
     * 把**覆盖前**的内容保留一份为 {@code routes.json.bak}（★ 审计 R3-P3-4）。
     *
     * <p>必须在 {@code writeAtomic} 之前调用 —— 那时 {@code path} 还是上一版内容。
     * 于是 .bak 恒为「上一代可用数据」；配合 {@code loadAll} 的
     * {@code .corrupt-<ts>} 隔离，玩家至少有两代数据可恢复。</p>
     *
     * <p>任何失败都只记日志：备份是附加保障，不能影响保存本身。</p>
     */
    private static void backupPrevious(Path path) {
        try {
            if (!Files.exists(path)) {
                return;
            }
            Path backup = path.resolveSibling(path.getFileName() + ".bak");
            Files.copy(path, backup,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("备份上一版手绘路线失败（不影响本次保存）: {}", path, e);
        }
    }

    /**
     * 原子写入：先写同目录下的 .tmp，再 move 覆盖目标。
     *
     * <p>直接往目标文件写的话，写到一半崩溃会留下半个 JSON —— 那样**所有路线全丢**。
     * 用临时文件 + 原子 rename 可以保证「要么是旧的完整内容、要么是新的完整内容」。</p>
     *
     * <p>tmp 名字带唯一后缀（调用方已用 per-level 锁串行，这里是双重保险：
     * 万一将来有人在锁外调用，也不会两个线程写同一个文件）。</p>
     */
    private static void writeAtomic(Path target, String content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + "."
                + Long.toHexString(System.nanoTime()) + ".tmp");
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                // 某些文件系统不支持原子 move，退回普通覆盖（仍是先写 tmp 再覆盖）
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            // move 成功后 tmp 已不存在；失败时清掉，避免目录里堆垃圾
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // 清理失败不影响主流程
            }
        }
    }
}
