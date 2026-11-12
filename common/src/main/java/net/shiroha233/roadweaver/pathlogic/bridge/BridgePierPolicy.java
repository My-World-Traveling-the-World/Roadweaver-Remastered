/* 文件职责：解释桥墩配置，且刻意不加载 Minecraft 注册表（纯配置解释，可在无 MC 环境单测）。 */
package net.shiroha233.roadweaver.pathlogic.bridge;

/** Interprets bridge pier configuration without loading Minecraft registries. */
final class BridgePierPolicy {
    private BridgePierPolicy() {
    }

    static boolean isEnabled(int maxHeight) {
        return maxHeight > 0;
    }
}
