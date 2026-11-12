package net.shiroha233.roadweaver.compat;

/**
 * 唯一直接引用长城 api 的类 —— 目的是把"没装长城"时的类加载风险关在这一个文件里。
 *
 * <p>调用方（{@link GreatWallRoadGuard}）必须先问 {@link #present()}，只有 true 才调用下面的方法。
 * 若长城不在，本类的这些方法永远不会被执行，JVM 也就不会去解析 {@code WallRoadApi}。
 */
final class WallRoadAdapter {
    private static volatile boolean resolved;
    private static volatile boolean present;

    private WallRoadAdapter() {
    }

    /** 长城是否带 api（用 Class.forName 探一次，之后缓存）。 */
    static boolean present() {
        if (resolved) return present;
        synchronized (WallRoadAdapter.class) {
            if (resolved) return present;
            try {
                Class.forName("local.plaincraft.greatwall.api.WallRoadApi", false,
                        WallRoadAdapter.class.getClassLoader());
                present = true;
            } catch (Throwable absent) {
                present = false;
            }
            resolved = true;
            return present;
        }
    }

    static boolean blocks(int x, int y, int z) {
        return local.plaincraft.greatwall.api.WallRoadApi.blocks(x, y, z);
    }

    static boolean blocksWithin(int x, int y, int z, int radius) {
        return local.plaincraft.greatwall.api.WallRoadApi.blocksWithin(x, y, z, radius);
    }

    static boolean coarseMayAffect(int x, int z) {
        return local.plaincraft.greatwall.api.WallRoadApi.coarseMayAffect(x, z);
    }

    static long millisSinceLastMark() {
        return local.plaincraft.greatwall.api.WallRoadApi.millisSinceLastMark();
    }

    static boolean hasMarks() {
        return local.plaincraft.greatwall.api.WallRoadApi.hasMarks();
    }
}
