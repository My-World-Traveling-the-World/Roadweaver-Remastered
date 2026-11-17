/* 文件职责：有限容量的弱身份缓存，避免内容哈希扫描和长期强持有道路。 */
package net.shiroha233.roadweaver.util;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

/** 只按实例命中；值不得反向持有键，回收后在下次访问时排空失效项。 */
public final class WeakIdentityCache<K, V> {
    private final ReferenceQueue<K> collected = new ReferenceQueue<>();
    private final Map<Object, V> values = new HashMap<>();
    private final Lookup<K> lookup = new Lookup<>();
    private final int capacity;

    public WeakIdentityCache(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    public synchronized V get(K key) {
        drain();
        lookup.value = java.util.Objects.requireNonNull(key);
        try { return values.get(lookup); }
        finally { lookup.value = null; }
    }

    public synchronized boolean containsKey(K key) {
        drain();
        lookup.value = java.util.Objects.requireNonNull(key);
        try { return values.containsKey(lookup); }
        finally { lookup.value = null; }
    }

    public synchronized void put(K key, V value) {
        drain();
        Key<K> reference = new Key<>(key, collected);
        // 保留原有整批容量策略；覆盖同一实例不应驱逐其他仍可复用的数据。
        if (values.size() >= capacity && !values.containsKey(reference)) values.clear();
        values.put(reference, value);
    }

    public synchronized int size() {
        drain();
        return values.size();
    }

    public synchronized void clear() {
        values.clear();
        drain();
    }

    private void drain() {
        java.lang.ref.Reference<? extends K> key;
        while ((key = collected.poll()) != null) values.remove(key);
    }

    /** 查询键仅在同步方法持锁期间持有对象，不能入表；复用以消除热命中临时引用分配。 */
    private static final class Lookup<K> {
        K value;
        @Override public int hashCode() { return System.identityHashCode(value); }
        @Override public boolean equals(Object other) {
            return other instanceof Key<?> key && value != null && value == key.get();
        }
    }

    private static final class Key<K> extends WeakReference<K> {
        private final int hash;

        Key(K key, ReferenceQueue<K> queue) {
            super(java.util.Objects.requireNonNull(key), queue);
            hash = System.identityHashCode(key);
        }

        @Override public int hashCode() { return hash; }

        @Override public boolean equals(Object other) {
            // 空引用只能认自己，否则不同已回收键会错误互相命中。
            return this == other || other instanceof Key<?> that && get() != null && get() == that.get();
        }
    }
}
