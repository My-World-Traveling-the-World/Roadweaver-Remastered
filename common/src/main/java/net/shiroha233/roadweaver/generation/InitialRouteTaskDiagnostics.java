/* 文件职责：每条路的启动诊断，用来在进入世界之前发现「静默等待」。 */
package net.shiroha233.roadweaver.generation;

// 日志保留原诊断标记与字段以兼容分析工具；中文主说明使用编译期常量，避免热路径翻译开销。

import net.shiroha233.roadweaver.core.model.StructureConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** 逐路开局诊断用于定位进档前的静默等待，不参与路径选择。 */
public final class InitialRouteTaskDiagnostics {
    private InitialRouteTaskDiagnostics() {}

    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    private static final ConcurrentHashMap<Long, Entry> ACTIVE = new ConcurrentHashMap<>();
    private static final AtomicLong HEARTBEAT_SEQUENCE = new AtomicLong();

    public record Snapshot(long taskId, StructureConnection connection, int step,
                           String state, String waitingOn, String thread,
                           long elapsedMs, long idleMs, int progressPermille,
                           long heartbeat) {}

    public static void register(long taskId, StructureConnection connection, int step, String state) {
        if (taskId <= 0) return;
        ACTIVE.put(taskId, new Entry(taskId, connection, step, state));
        heartbeat(taskId, state, "none", 0);
    }

    public static void heartbeat(long taskId, String state, String waitingOn, int progressPermille) {
        Entry entry = ACTIVE.get(taskId);
        if (entry == null) return;
        long now = System.nanoTime();
        entry.lastHeartbeatNs = now;
        entry.lastHeartbeat = HEARTBEAT_SEQUENCE.incrementAndGet();
        if (state != null && !state.isBlank()) entry.state = state;
        if (waitingOn != null && !waitingOn.isBlank()) entry.waitingOn = waitingOn;
        entry.progressPermille = Math.max(entry.progressPermille, Math.max(0, Math.min(999, progressPermille)));
        entry.thread = Thread.currentThread().getName();
    }

    public static void waiting(long taskId, String waitingOn) {
        heartbeat(taskId, null, waitingOn, -1);
    }

    public static void finish(long taskId) {
        ACTIVE.remove(taskId);
    }

    public static Snapshot snapshot(long taskId) {
        Entry e = ACTIVE.get(taskId);
        return e == null ? null : e.snapshot();
    }

    public static Snapshot findFor(StructureConnection connection, int step) {
        for (Entry entry : ACTIVE.values()) {
            if (entry.step == step && sameConnection(entry.connection, connection)) return entry.snapshot();
        }
        return null;
    }

    /**
     * 单条 watchdog 日志最多列出的连接数。
     *
     * <p>0.8.356：原先无上限地列出**全部** pending 连接，1024 区块大规模预热时
     * pending 可达 629 条、单行 8 万字符、每 5 秒一条 —— 实测一局刷出
     * **309 行 / 973 万字符 / 占日志文件 38.2%**（24 MB 日志的主因）。
     * 该日志的价值在于「看出哪些卡住了、卡在哪个 state」，629 条里绝大多数是
     * 正常 QUEUED，全量输出没有额外信息量。现限制为前 {@value} 条，
     * 其余折叠成一行计数摘要。</p>
     */
    private static final int MAX_LOGGED_TASKS = 10;

    public static void logBatch(int step, List<StructureConnection> pending) {
        if (pending == null || pending.isEmpty()) return;
        int limit = Math.min(MAX_LOGGED_TASKS, pending.size());
        List<String> states = new ArrayList<>(limit + 1);
        // 未注册任务（QUEUED）与已注册任务（有 state 心跳）分别计数：
        // 只看一行摘要就能判断「是排队积压还是真的卡在某个 state 上」。
        int queued = 0;
        int tracked = 0;
        for (int i = 0; i < pending.size(); i++) {
            StructureConnection connection = pending.get(i);
            Snapshot snapshot = findFor(connection, step);
            if (snapshot == null) {
                queued++;
            } else {
                tracked++;
            }
            if (i < limit) {
                states.add(snapshot == null
                        ? shortEndpoint(connection) + " state=QUEUED"
                        : shortEndpoint(snapshot.connection())
                        + " state=" + snapshot.state()
                        + " progress=" + snapshot.progressPermille() + "/999"
                        + " elapsedMs=" + snapshot.elapsedMs()
                        + " idleMs=" + snapshot.idleMs()
                        + " waitingOn=" + snapshot.waitingOn()
                        + " thread=" + snapshot.thread());
            }
        }
        if (states.isEmpty()) {
            states.add("(none)");
        }
        String suffix = pending.size() > limit
                ? " ... (+" + (pending.size() - limit) + " more; queued=" + queued + " tracked=" + tracked + ")"
                : " (queued=" + queued + " tracked=" + tracked + ")";
        LOGGER.info("【初始道路等待任务诊断】 " + "RoadWeaver initial route watchdog: step={} pending={} {}{}",
                step, pending.size(), String.join(" | ", states), suffix);
    }

    /** 端点缩写：完整 {@code StructureConnection.toString()} 很长，watchdog 只需可定位。 */
    private static String shortEndpoint(StructureConnection connection) {
        if (connection == null) return "?";
        return "(" + connection.from().getX() + "," + connection.from().getZ() + ")->("
                + connection.to().getX() + "," + connection.to().getZ() + ")";
    }

    private static boolean sameConnection(StructureConnection a, StructureConnection b) {
        return a != null && b != null && a.from().equals(b.from()) && a.to().equals(b.to());
    }

    private static final class Entry {
        private final long taskId;
        private final StructureConnection connection;
        private final int step;
        private final long startedNs = System.nanoTime();
        private volatile long lastHeartbeatNs = startedNs;
        private volatile long lastHeartbeat;
        private volatile String state;
        private volatile String waitingOn = "none";
        private volatile String thread = "-";
        private volatile int progressPermille;

        private Entry(long taskId, StructureConnection connection, int step, String state) {
            this.taskId = taskId;
            this.connection = connection;
            this.step = step;
            this.state = state == null || state.isBlank() ? "queued" : state;
        }

        private Snapshot snapshot() {
            long now = System.nanoTime();
            return new Snapshot(taskId, connection, step, state, waitingOn, thread,
                    (now - startedNs) / 1_000_000L,
                    (now - lastHeartbeatNs) / 1_000_000L,
                    progressPermille, lastHeartbeat);
        }
    }
}
