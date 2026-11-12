/* 文件职责：按版本号失效的只读快照缓存（通用、可确定性测试）。 */
package net.shiroha233.roadweaver.persistence.files;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 版本失效的只读快照缓存，供 {@link StructureFileStorage} 去掉
 * {@code getStructureSnapshot} 的重复读盘。
 *
 * <p>语义：每个 key 持有一个递增的 {@code version}。{@link #getOrLoad}
 * 若缓存的 entry 版本与当前版本一致则直接复用（不触发 loader），否则用
 * loader 重新构造并缓存；{@link #bump} 递增版本并丢弃旧 entry，使下一次
 * get 重建快照。</p>
 *
 * <p>所有方法都在同一把实例锁上 {@code synchronized}，bump 与 get 之间
 * 有 happens-before 保证，不会读到半写状态；快照对象本身须是不可变或
 * 防御性拷贝的，由调用方保证（这里 {@code S} 即
 * {@link StructureFileStorage.StructureSnapshot}）。</p>
 */
final class VersionedSnapshotCache<K, S> {

    private static final class Entry<S> {
        final long version;
        final S snapshot;

        Entry(long version, S snapshot) {
            this.version = version;
            this.snapshot = snapshot;
        }
    }

    private final Map<K, Entry<S>> cache = new HashMap<>();
    private final Map<K, Long> versions = new HashMap<>();

    synchronized S getOrLoad(K key, Supplier<S> loader) {
        long version = versions.getOrDefault(key, 0L);
        Entry<S> cached = cache.get(key);
        if (cached != null && cached.version == version) {
            return cached.snapshot;
        }
        S snapshot = loader.get();
        cache.put(key, new Entry<>(version, snapshot));
        return snapshot;
    }

    synchronized void bump(K key) {
        versions.put(key, versions.getOrDefault(key, 0L) + 1L);
        cache.remove(key);
    }

    synchronized long version(K key) {
        return versions.getOrDefault(key, 0L);
    }

    synchronized void reset() {
        cache.clear();
        versions.clear();
    }

    synchronized int size() {
        return cache.size();
    }
}
