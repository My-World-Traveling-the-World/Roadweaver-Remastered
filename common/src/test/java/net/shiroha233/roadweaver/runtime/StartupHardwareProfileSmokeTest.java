package net.shiroha233.roadweaver.runtime;

/** Verifies the native exact-structure lane scales independently of the shared lane. */
public final class StartupHardwareProfileSmokeTest {
    private StartupHardwareProfileSmokeTest() {}

    public static void main(String[] args) {
        StartupHardwareProfile desktop = StartupHardwareProfile.resolve(16, 17L * 1024L * 1024L * 1024L, 0);
        require(desktop.roadWorkers() == 14, "desktop road budget changed");
        require(desktop.structureWorkers() == 6, "desktop structure pool changed");
        require(desktop.nativeStructureWorkers() == 12, "desktop native exact lane did not scale to startup budget");

        StartupHardwareProfile highEnd = StartupHardwareProfile.resolve(32, 48L * 1024L * 1024L * 1024L, 0);
        require(highEnd.nativeStructureWorkers() == 16, "high-end native exact lane is not capped at 16");

        StartupHardwareProfile lowMemory = StartupHardwareProfile.resolve(8, 4L * 1024L * 1024L * 1024L, 0);
        require(lowMemory.nativeStructureWorkers() == 2, "low-memory native exact lane was over-allocated");
        System.out.println("StartupHardwareProfileSmokeTest PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
