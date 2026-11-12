package net.shiroha233.roadweaver.compat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 道路侧访问长城只读守卫的唯一入口。
 *
 * <p>0.8.460 起不再用反射：长城把接口编译进一个只含 {@code api} 包的极小 jar，
 * 道路侧 {@code compileOnly} 依赖它，于是**签名一旦变化编译就失败**，不会再静默失效。
 * 没装长城时的风险全部关在 {@link WallRoadAdapter} 里（先用 {@code present()} 挡住，
 * 永不执行引用长城类的方法）。
 */
public final class GreatWallRoadGuard {
    private static final Logger LOGGER = LoggerFactory.getLogger("RoadWeaver/GreatWallRoadGuard");

    /** 哪条写块通道在问，用于分通道计数与日志。 */
    public enum Ch {
        SURFACE, CLEAR, BRIDGE, BUOY, DECO, DENSITY,
        /**
         * 施工期「挖方」通道：路肩削坡、横断面开山、隧道贯通清障。
         *
         * <p>这一路过去**没有任何护栏**，是 0.8.460 之前把墙挖成通高竖缝的真凶：
         * 长城永远高于地表，高度图把墙顶当成「地面」，施工期就把整面墙当成一段
         * 十几格深的横断面土方去削平。删方块的入口现在统一走这个通道。
         */
        CUT
    }

    private static final Map<Ch, AtomicLong> SKIPS = new ConcurrentHashMap<>();
    private static final Set<Long> CONTACT_CELLS = ConcurrentHashMap.newKeySet();
    private static final List<int[]> CONTACTS = Collections.synchronizedList(new ArrayList<>());
    private static volatile boolean blockedLogged;
    private static volatile boolean failureLogged;

    private GreatWallRoadGuard() {
    }

    // ---------------- 内部 ----------------

    private static boolean active() {
        if (!WallRoadAdapter.present()) return false;
        try {
            return true;
        } catch (Throwable failure) {
            if (!failureLogged) {
                failureLogged = true;
                LOGGER.warn("Great Wall road guard unavailable ({}); roads keep laying as before",
                        failure.toString());
            }
            return false;
        }
    }

    private static void note(Ch ch) {
        long n = SKIPS.computeIfAbsent(ch, unused -> new AtomicLong()).incrementAndGet();
        if (n % 500L == 0L) {
            LOGGER.info("ROAD_GUARD_SKIP channel={} count={}", ch, n);
        }
    }

    private static void recordContact(int x, int y, int z) {
        long cell = (((long) (x >> 3)) << 32) ^ ((z >> 3) & 0xFFFFFFFFL);
        if (!CONTACT_CELLS.add(cell)) return;
        CONTACTS.add(new int[]{x, y, z});
        if (CONTACTS.size() <= 40) {
            LOGGER.info("ROAD_WALL_CONTACT #{} at {} {} {}", CONTACTS.size(), x, y, z);
        }
    }

    private static void firstHit() {
        if (blockedLogged) return;
        blockedLogged = true;
        LOGGER.info("Great Wall corridor detected; roads will stop at the wall foot");
    }

    /** Clears contact/skip diagnostics when a server/world session ends. */
    public static void resetDiagnostics() {
        CONTACT_CELLS.clear();
        CONTACTS.clear();
        SKIPS.clear();
        blockedLogged = false;
        failureLogged = false;
        coarseHits = 0L;
    }

    // ---------------- 对外 ----------------

    /** 这一格是否被长城占用（含 2 格余量）。 */
    public static boolean blocks(int x, int y, int z) {
        if (!active()) return false;
        try {
            boolean stopped = WallRoadAdapter.blocks(x, y, z);
            if (stopped) firstHit();
            return stopped;
        } catch (Throwable failure) {
            if (!failureLogged) {
                failureLogged = true;
                LOGGER.warn("Great Wall road guard call failed ({}); failing closed to protect the wall",
                        failure.toString());
            }
            // The wall mod is present but its guard is broken. Treat the target as protected;
            // silently returning false here is how a road could demolish a wall after a bad API call.
            return true;
        }
    }

    /** 同上，余量自定义（灯柱/路牌这类横向有伸展的道具用 3）。 */
    public static boolean blocksWithin(int x, int y, int z, int radius) {
        if (!active()) return false;
        try {
            boolean stopped = WallRoadAdapter.blocksWithin(x, y, z, radius);
            if (stopped) firstHit();
            return stopped;
        } catch (Throwable failure) {
            // Preserve the caller's requested radius instead of silently shrinking it.
            return true;
        }
    }

    /** 带通道标记的 blocks（用于计数与交界点上报）。 */
    public static boolean blocksFor(Ch ch, int x, int y, int z) {
        boolean stopped = blocks(x, y, z);
        if (stopped) {
            note(ch);
            if (ch == Ch.SURFACE) recordContact(x, y, z);
        }
        return stopped;
    }

    /** 带通道标记的 blocksWithin。 */
    public static boolean blocksWithinFor(Ch ch, int x, int y, int z, int radius) {
        boolean stopped = blocksWithin(x, y, z, radius);
        if (stopped) note(ch);
        return stopped;
    }

    /** 高热点用的粗筛（密度通道），不计数、不打日志。 */
    public static boolean coarseBlocks(int x, int z) {
        if (!active()) return false;
        try {
            boolean hit = WallRoadAdapter.coarseMayAffect(x, z);
            if (hit) noteCoarseHit();
            return hit;
        } catch (Throwable failure) {
            return false;
        }
    }

    /**
     * 粗筛命中计数（诊断用）。密度通道是每方块调用一次的热点，所以这里故意用非原子 long：
     * 只用来量"影响面有多大"，丢几次更新无所谓。每 2^20 次打一行。
     */
    private static long coarseHits;

    private static void noteCoarseHit() {
        long n = ++coarseHits;
        if ((n & 0xFFFFFL) == 0L) {
            LOGGER.info("ROAD_GUARD_COARSE_SKIP hits={} (density channel: road terrain stamping suppressed here)", n);
        }
    }

    /** 长城是否在位（诊断用）。 */
    public static boolean isAvailable() {
        return WallRoadAdapter.present();
    }

    /** 已上报的交界点（x,y,z），供长城侧读取后放便门。 */
    public static List<int[]> contacts() {
        synchronized (CONTACTS) {
            return new ArrayList<>(CONTACTS);
        }
    }

    /** 交界点数量。 */
    public static int contactCount() {
        return CONTACTS.size();
    }
}
