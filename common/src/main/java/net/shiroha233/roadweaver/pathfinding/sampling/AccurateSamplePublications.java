/* 文件职责：用固定容量发布版本识别过期的精确采样未命中快照。 */
package net.shiroha233.roadweaver.pathfinding.sampling;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/** 每个采样上下文独有；只决定是否重查，不承载样本，也不替代缓存或瓦片认领。 */
final class AccurateSamplePublications {
    // 精确逐瓦片版本会随世界探索增长；固定 8 KiB 槽位，碰撞最多增加重查，绝不宣告命中。
    private static final int STRIPES = 1024;
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLongArray latest = new AtomicLongArray(STRIPES);

    long version() { return sequence.get(); }

    boolean changedSince(Collection<Long> tileKeys, long observedVersion) {
        for (long key : tileKeys) {
            if (latest.get(stripe(key)) > observedVersion) return true;
        }
        return false;
    }

    /** 完整发布缓存后、唤醒等待者之前记录，避免旧快照在认领已释放后直接重算。 */
    void publishedTiles(Collection<Long> tileKeys) {
        if (tileKeys.isEmpty()) return;
        long version = sequence.incrementAndGet();
        for (long key : tileKeys) {
            // 多批发布可以交错；旧批次晚写不能把新版本覆盖回去。
            latest.accumulateAndGet(stripe(key), version, Math::max);
        }
    }

    private static int stripe(long key) {
        // 先混合坐标，避免简单 x^z 让长对角线的所有瓦片挤在同一个槽位。
        key ^= key >>> 33;
        key *= 0xff51afd7ed558ccdL;
        key ^= key >>> 33;
        return (int) key & (STRIPES - 1);
    }
}
