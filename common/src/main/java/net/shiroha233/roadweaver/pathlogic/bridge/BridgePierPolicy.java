package net.shiroha233.roadweaver.pathlogic.bridge;

/** Interprets bridge pier configuration without loading Minecraft registries. */
final class BridgePierPolicy {
    private BridgePierPolicy() {
    }

    static boolean isEnabled(int maxHeight) {
        return maxHeight > 0;
    }
}
