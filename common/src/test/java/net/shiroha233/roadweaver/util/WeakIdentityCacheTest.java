/* 文件职责：用不可调用内容哈希的键检验弱身份和容量语义。 */
package net.shiroha233.roadweaver.util;
import org.junit.jupiter.api.Test;
import java.lang.ref.Reference;
import java.lang.reflect.Field;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class WeakIdentityCacheTest {
    static class Key {
        @Override public int hashCode() { throw new AssertionError("内容哈希不应被调用"); }
        @Override public boolean equals(Object other) { return other instanceof Key; }
    }
    @Test void identityLookupNeverCallsContentHashAndKeepsEqualInstancesDistinct() {
        var cache = new WeakIdentityCache<Key,String>(2);
        var a = new Key(); var b = new Key(); var c = new Key();
        cache.put(a,"a"); cache.put(b,"b");
        assertEquals("a", cache.get(a)); assertEquals("b", cache.get(b));
        assertNull(cache.get(c));
        assertFalse(cache.containsKey(c)); assertTrue(cache.containsKey(a));
        try {
            var lookupField=WeakIdentityCache.class.getDeclaredField("lookup"); lookupField.setAccessible(true);
            var lookup=lookupField.get(cache);
            var valueField=lookup.getClass().getDeclaredField("value"); valueField.setAccessible(true);
            assertNull(valueField.get(lookup),"复用查询键不能跨调用强持有道路");
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }

        cache.put(a,"updated"); assertEquals(2,cache.size());
        cache.put(c,"c"); assertEquals(1,cache.size());
        assertNull(cache.get(a)); assertEquals("c",cache.get(c));
        cache.clear(); assertEquals(0,cache.size());
    }
    @Test void queuedWeakKeysAreRemovedWithoutDependingOnGcScheduling() throws Exception {
        var cache = new WeakIdentityCache<Key,String>(4);
        var a = new Key(); var b = new Key(); cache.put(a,"a"); cache.put(b,"b");
        Field field = WeakIdentityCache.class.getDeclaredField("values"); field.setAccessible(true);
        Map<?,?> entries = (Map<?,?>)field.get(cache);
        Reference<?> reference = (Reference<?>)entries.keySet().iterator().next();
        // 主动入队检验真实排空路径，避免靠GC时序写偶发测试；另一个键仍应存在。
        reference.clear(); assertTrue(reference.enqueue()); assertEquals(1,cache.size());
        assertEquals(1, (cache.get(a)==null ? 0 : 1)+(cache.get(b)==null ? 0 : 1));
    }
}
