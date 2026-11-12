package net.shiroha233.roadweaver.pathlogic.tunnel;

import static net.shiroha233.roadweaver.pathlogic.structures.WorldgenAvoidance.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 0.9.065：[TUNNEL-CLEAR] 每段（span）一行的清空台账。
 *
 * <p>要回答的问题只有一个：隧道 span 的<b>洞身站</b>到底有没有走「向上清空」那条路。三个计数：</p>
 * <ul>
 *   <li>{@code bodyStations}  —— 已建成的洞身站数（岩体本身就是顶的那些站）；</li>
 *   <li>{@code skippedClear}  —— 洞身站上被<b>跳过</b>向上清空的路面列数（每个洞身列 +1）；</li>
 *   <li>{@code portalCleared} —— 洞口明挖段上照常做了向上清空的路面列数（行为必须与改前一致）。</li>
 * </ul>
 *
 * <p>一行对一段隧道：{@link #report} 只在 span 的最后一站铺完之后打，重复调用不会重复打。
 * 计数是运行期观察、不是持久状态；{@link RoadTunnelBuilder#clearAll()} 在存档/维度切换时
 * 会调 {@link #reset()} 清掉。</p>
 */
public final class TunnelClearLedger {

    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");

    /** 一个 TUNNEL span 的站区间（闭区间，构造时自动排序）。 */
    public record Scope(int spanFirst, int spanLast) {
        public Scope {
            int first = Math.min(spanFirst, spanLast);
            int last = Math.max(spanFirst, spanLast);
            spanFirst = first;
            spanLast = last;
        }
    }

    private static final class Counts {
        private long bodyStations;
        private long skippedClear;
        private long portalCleared;
        private boolean reported;
    }

    private static final Map<Scope, Counts> COUNTS = new ConcurrentHashMap<>();

    private TunnelClearLedger() {
    }

    /** 清空全部计数（存档/维度切换，以及单测之间）。 */
    public static void reset() {
        COUNTS.clear();
    }

    public static void noteBodyStation(Scope scope) {
        if (scope == null) return;
        Counts counts = counts(scope);
        synchronized (counts) {
            counts.bodyStations++;
        }
    }

    /** 记「这一列是洞身列，向上清空被跳过」。 */
    public static void noteSkippedClear(Scope scope, int columns) {
        if (scope == null || columns <= 0) return;
        Counts counts = counts(scope);
        synchronized (counts) {
            counts.skippedClear += columns;
        }
    }

    /** 记「这一列是洞口明挖列，照常向上清空」。 */
    public static void notePortalClear(Scope scope, int columns) {
        if (scope == null || columns <= 0) return;
        Counts counts = counts(scope);
        synchronized (counts) {
            counts.portalCleared += columns;
        }
    }

    public static long bodyStations(Scope scope) {
        return scope == null ? 0L : read(scope).bodyStations;
    }

    public static long skippedClear(Scope scope) {
        return scope == null ? 0L : read(scope).skippedClear;
    }

    public static long portalCleared(Scope scope) {
        return scope == null ? 0L : read(scope).portalCleared;
    }

    /**
     * 诊断行本体：
     * {@code [TUNNEL-CLEAR] span=<a..b> bodyStations=<n> skippedClear=<n> portalCleared=<n>}
     */
    public static String line(Scope scope) {
        if (scope == null) {
            return "[TUNNEL-CLEAR] span=none bodyStations=0 skippedClear=0 portalCleared=0";
        }
        Counts counts = counts(scope);
        synchronized (counts) {
            return "[TUNNEL-CLEAR] span=" + scope.spanFirst() + ".." + scope.spanLast()
                    + " bodyStations=" + counts.bodyStations
                    + " skippedClear=" + counts.skippedClear
                    + " portalCleared=" + counts.portalCleared;
        }
    }

    /** 一段隧道一行；同一个 span 只打一次日志（重复调用只返回同一行文本）。 */
    public static String report(Scope scope) {
        String line = line(scope);
        if (scope == null) {
            LOGGER.info(line);
            return line;
        }
        Counts counts = counts(scope);
        synchronized (counts) {
            if (counts.reported) return line;
            counts.reported = true;
        }
        LOGGER.info(line);
        return line;
    }

    private static Counts read(Scope scope) {
        Counts counts = COUNTS.get(scope);
        return counts == null ? new Counts() : counts;
    }

    private static Counts counts(Scope scope) {
        return COUNTS.computeIfAbsent(scope, ignored -> new Counts());
    }
}
