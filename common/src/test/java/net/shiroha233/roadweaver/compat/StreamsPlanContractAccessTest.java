package net.shiroha233.roadweaver.compat;

import dev.streamsreflowing.api.RiverPlanApi;
import java.net.URLClassLoader;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 真契约、真消费者适配及真采样会话；提供方端口只替代世界/引擎。 */
class StreamsPlanContractAccessTest {
    @Test void contractMapsColumnsLakesPlatesAndRealSamplingCache() throws Exception {
        var calls = new AtomicInteger();
        var builds = new AtomicInteger();
        Object identity = new Object();
        var session = new RiverPlanApi.Session() {
            public Object identity() { return identity; }
            public RiverPlanApi.Column column(int x, int z) {
                calls.incrementAndGet();
                if (x == 99) throw new IllegalStateException("查询故障");
                return new RiverPlanApi.Column(x < 0, x < 0, 78, 72, 3.5, 6);
            }
            public RiverPlanApi.Lake lake(int x, int z) { return new RiverPlanApi.Lake(x < 0, 80, 79); }
            public List<RiverPlanApi.Plate> platesNear(double x, double z, double radius) {
                assertEquals(64, radius);
                return List.of(new RiverPlanApi.Plate(-2, 3));
            }
            public void prewarm(RiverPlanApi.Plate plate) {
                assertEquals(new RiverPlanApi.Plate(-2, 3), plate); builds.incrementAndGet();
            }
            public RiverPlanApi.Startup startup() { return new RiverPlanApi.Startup(11, RiverPlanApi.Phase.COMPLETE, ""); }
        };
        RiverPlanApi.register(new RiverPlanApi.Provider() {
            public RiverPlanApi.Session open(Object world) {
                if (world != identity) throw new IllegalStateException("错误世界");
                return session;
            }
            public RiverPlanApi.Startup startup(Object world) { return session.startup(); }
        });
        assertSame(session, StreamsPlanContractAccess.openHandle(identity));
        assertThrows(IllegalStateException.class, () -> StreamsPlanContractAccess.openHandle(null));
        var access = new StreamsPlanContractAccess();
        assertSame(identity, access.identity(session));
        assertEquals(new StreamsReflowingCompat.RiverColumn(true, true, 78, 72, 3.5, 6),
                access.column(session, -20, -30));
        assertSame(StreamsReflowingCompat.RiverColumn.NONE, access.column(session, 1, 1));
        assertEquals(new StreamsReflowingCompat.LakeColumn(true, 80, 79), access.lake(session, -1, 1));
        assertSame(StreamsReflowingCompat.LakeColumn.NONE, access.lake(session, 1, 1));
        var plates = access.platesNear(session, -100, 200, 64);
        assertEquals(List.of(new RiverPlanApi.Plate(-2, 3)), plates);
        access.prewarm(session, plates.getFirst()); assertEquals(1, builds.get());
        // 真实采样缓存与有界查询仍生效，迁契约不能绕过既有线程/超时边界。
        var constructor = StreamsReflowingCompat.SamplingSession.class.getDeclaredConstructor(
                StreamsPlanAccess.class, Object.class, boolean.class);
        constructor.setAccessible(true);
        var sampling = constructor.newInstance(access, session, false);
        calls.set(0);
        var first = sampling.sample(-4, -9);
        assertSame(first, sampling.sample(-4, -9));
        assertEquals(1, calls.get());
        assertTrue(sampling.sample(99, 0).isUnknown());
        assertFalse(sampling.sample(-4, -9).isUnknown());
        StreamsReflowingCompat.clearPrewarmState();
    }

    @Test void absentApiDoesNotPreventMainCompatibilityClassLoading() throws Exception {
        var location = StreamsReflowingCompat.class.getProtectionDomain().getCodeSource().getLocation();
        try (var loader = new URLClassLoader(new java.net.URL[]{location}, getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("dev.streamsreflowing.")) throw new ClassNotFoundException(name);
                if (name.startsWith("net.shiroha233.roadweaver.compat.Streams")) {
                    synchronized (getClassLoadingLock(name)) {
                        Class<?> type = findLoadedClass(name);
                        if (type == null) type = findClass(name);
                        if (resolve) resolveClass(type);
                        return type;
                    }
                }
                return super.loadClass(name, resolve);
            }
        }) {
            Class<?> type = loader.loadClass(StreamsReflowingCompat.class.getName());
            assertEquals(false, type.getMethod("isAvailable").invoke(null));
        }
    }

    @Test void oldProviderReflectionPortStillReadsTheOriginalContract() throws Exception {
        var type = Class.forName(StreamsReflowingCompat.class.getName() + "$LegacyAccess");
        var methods = new java.lang.reflect.Method[] {
                LegacyFixture.class.getMethod("engine", net.minecraft.server.level.ServerLevel.class),
                LegacyFixture.class.getMethod("plan", int.class, int.class),
                LegacyFixture.class.getMethod("lakeAt", int.class, int.class),
                LegacyFixture.class.getMethod("plateAt", int.class, int.class),
                LegacyFixture.class.getMethod("platesNear", double.class, double.class, double.class),
                LegacyFixture.class.getMethod("networkFor", Object.class),
                OldPlan.class.getMethod("inChannel"), OldPlan.class.getMethod("inCorridor"),
                OldPlan.class.getMethod("waterSurfaceY"), OldPlan.class.getMethod("bedY"),
                OldPlan.class.getMethod("distance"), OldPlan.class.getMethod("width"),
                OldLake.class.getMethod("level"), OldLake.class.getMethod("digDepth")
        };
        var constructor = type.getDeclaredConstructors()[0]; constructor.setAccessible(true);
        var access = (StreamsPlanAccess) constructor.newInstance((Object[]) methods);
        var fixture = new LegacyFixture();
        assertEquals(new StreamsReflowingCompat.RiverColumn(true, true, 75, 68, 2, 7),
                access.column(fixture, -3, 2));
        assertEquals(new StreamsReflowingCompat.LakeColumn(true, 80, 79), access.lake(fixture, 3, 4));
        assertEquals(List.of("板块"), access.platesNear(fixture, 3, 4, 64));
        access.prewarm(fixture, "板块"); assertEquals(1, fixture.built);
    }

    public record OldPlan(boolean inChannel, boolean inCorridor, int waterSurfaceY, int bedY,
                          double distance, double width) {}
    public record OldLake(int level, int digDepth) {}
    public static class LegacyFixture {
        int built;
        public static Object engine(net.minecraft.server.level.ServerLevel level) { return null; }
        public OldPlan plan(int x, int z) { return new OldPlan(true, true, 75, 68, 2, 7); }
        public OldLake lakeAt(int x, int z) { return new OldLake(80, 0); }
        public Object plateAt(int x, int z) { return "板块"; }
        public Object[] platesNear(double x, double z, double radius) { return new Object[]{"板块"}; }
        public void networkFor(Object plate) { assertEquals("板块", plate); built++; }
    }
}
