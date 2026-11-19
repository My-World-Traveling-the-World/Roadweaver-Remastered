package tannyjung.tanshugetrees_handcode.systems;

/** 仅存在于测试classpath的外部模组边界；让真实反射入口、后端及公开API全部参与回归。 */
public final class Caches {
    private Caches() {}
    public static final class TreeShape {
        public static short[] data = new short[0];
        public static Throwable failure;
        public static int reads;
        private TreeShape() {}
        public static short[] getTreeShapeData(String template) throws Throwable {
            reads++;
            if (failure != null) throw failure;
            return data;
        }
    }
}
