/* 文件职责：只为缓存和在途编译保留区块版本，防止历史失效键永久占内存。 */
package net.shiroha233.roadweaver.worldgen.road;

import java.util.HashMap;
import java.util.Map;

/** 所有方法由会话 cacheLock 保护，版本检查与缓存发布必须在同一临界区。 */
final class ChunkPlanVersions {
    static final class Claim {
        long revision;
        int users;
        boolean cached;
        Claim(long revision) { this.revision = revision; }
    }
    private final Map<Long, Claim> entries = new HashMap<>();
    private long sequence;

    Claim acquire(long key) {
        var entry = entries.computeIfAbsent(key, ignored -> new Claim(sequence));
        entry.users++;
        return entry;
    }
    long revision(long key) {
        var entry = entries.get(key);
        return entry == null ? sequence : entry.revision;
    }
    void release(long key, Claim entry) {
        if (--entry.users < 0) throw new IllegalStateException("区块计划编译认领重复释放");
        reap(key, entry);
    }
    void retained(long key) {
        var entry = entries.get(key);
        if (entry == null) throw new IllegalStateException("未认领的区块计划不能发布");
        entry.cached = true;
    }
    void evicted(long key) {
        var entry = entries.get(key);
        if (entry == null) return;
        entry.cached = false;
        reap(key, entry);
    }
    long advance() { return ++sequence; }
    void invalidate(long key, long revision) {
        var entry = entries.get(key);
        // 从未编译/已经淘汰的区块无旧结果可发布，不能为它建立永久版本条目。
        if (entry != null) entry.revision = revision;
    }
    void invalidateAll() {
        long revision = advance();
        entries.entrySet().removeIf(item -> {
            var entry = item.getValue();
            entry.revision = revision;
            entry.cached = false;
            return entry.users == 0;
        });
    }
    int size() { return entries.size(); }
    private void reap(long key, Claim entry) {
        // 在途旧编译仍引用此对象，必须等它退出才能删；否则会发生版本 ABA。
        if (!entry.cached && entry.users == 0) entries.remove(key, entry);
    }
}
