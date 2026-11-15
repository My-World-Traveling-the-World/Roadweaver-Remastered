/* 文件职责：只调度仍待施工的区块键，不读取世界或申请区块票据。 */
package net.shiroha233.roadweaver.generation;

import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongFunction;

final class DeferredChunkQueue {
    static final int BUILD_LIMIT = 8;
    static final int HINT_PROBE_LIMIT = 32;
    static final int SWEEP_PROBE_LIMIT = 128;

    enum Probe { WAITING, SETTLED, BUILDABLE }

    record Selection(List<Long> build, int settled, int hintSteps, int sweepSteps) {}

    // 单独记加载提示，避免把无关区块留下；轮询队列仍保留所有未结案键。
    private final LongLinkedOpenHashSet pending = new LongLinkedOpenHashSet();
    private final LongLinkedOpenHashSet hinted = new LongLinkedOpenHashSet();

    synchronized boolean add(long key) {
        return pending.add(key);
    }

    synchronized boolean remove(long key) {
        hinted.remove(key);
        return pending.remove(key);
    }

    synchronized void hint(long key) {
        if (pending.contains(key)) hinted.add(key);
    }

    synchronized int size() {
        return pending.size();
    }

    synchronized boolean isEmpty() {
        return pending.isEmpty();
    }

    /**
     * 至少留一个施工位给轮询，避免连续加载的新区块饿死启动时已经加载的旧区块。
     * 区块查询、台账和落块不拿队列锁，避免和异步入队、加载事件互等。
     */
    Selection select(LongFunction<Probe> probe) {
        List<Long> build = new ArrayList<>(BUILD_LIMIT);
        LongOpenHashSet visited = new LongOpenHashSet();
        int settled = 0;
        int hintSteps = 0;
        int sweepSteps = 0;
        while (hintSteps < HINT_PROBE_LIMIT && build.size() < BUILD_LIMIT - 1) {
            Long key = takeHint();
            if (key == null) break;
            hintSteps++;
            if (visited.add(key.longValue())) settled += inspect(key, probe, build);
        }

        // 限制探测数而非命中数；轮询从上次结束处继续，同一轮也不会绕完整队列两遍。
        int sweepBudget = Math.min(SWEEP_PROBE_LIMIT, size());
        while (sweepSteps < sweepBudget && build.size() < BUILD_LIMIT) {
            Long key = rotate();
            if (key == null) break;
            sweepSteps++;
            if (visited.add(key.longValue())) settled += inspect(key, probe, build);
        }

        // 若补漏没填满施工位，把空余位置交回提示；仍受同一轮提示额度约束。
        while (hintSteps < HINT_PROBE_LIMIT && build.size() < BUILD_LIMIT) {
            Long key = takeHint();
            if (key == null) break;
            hintSteps++;
            if (visited.add(key.longValue())) settled += inspect(key, probe, build);
        }
        return new Selection(build, settled, hintSteps, sweepSteps);
    }

    private static int inspect(long key, LongFunction<Probe> probe, List<Long> build) {
        return switch (probe.apply(key)) {
            case WAITING -> 0;
            case SETTLED -> 1;
            case BUILDABLE -> {
                build.add(key);
                yield 0;
            }
        };
    }

    private synchronized Long takeHint() {
        return hinted.isEmpty() ? null : hinted.removeFirstLong();
    }

    private synchronized Long rotate() {
        if (pending.isEmpty()) return null;
        long key = pending.firstLong();
        pending.addAndMoveToLast(key);
        // 轮询也会查当前加载状态，同一个提示无需再查一次。
        hinted.remove(key);
        return key;
    }
}
