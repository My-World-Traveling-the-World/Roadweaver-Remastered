/* 文件职责：路径控制点限幅松弛；端点、桥头与急弯约束在同一纯计算内维护。 */
package net.shiroha233.roadweaver.postprocess;

import net.minecraft.core.BlockPos;
import java.util.ArrayList;
import java.util.List;

/** 路径控制点限幅松弛；端点、桥头与急弯约束在同一纯计算内维护。 */
final class PathControlRelaxer {
    private PathControlRelaxer() {}

    /** 平滑迭代轮数。单轮 1-2-1 加权对 8 格锯齿只能削掉约 1/4 幅度，三轮才看得出「顺」。 */
    static final int RELAX_PASSES = 3;

    /**
     * 普通点的累计横向位移上限（格）。防止把真实弯道一路拉直。
     *
     * <p>0.9.025（H-02 的安全一半）：**2.0 → 3.0**。</p>
     *
     * <p>闭式关系是「残余 ≈ 锯齿幅 − cap」：锯齿峰 8 / 谷 0 时，cap 2.0 把峰 8→6、谷 0→2，
     * 净残余 **±2 格** —— 这正是真机 D1 里「沿河还有些抖」的那个 2。放到 3.0 后峰 8→5、谷 0→3，
     * 净残余 **±1 格**。</p>
     *
     * <p><b>为什么只到 3.0、不到 {@code d/2 = 4}</b>：3.0 仍然严格小于半个网格步长，
     * 「被挪动的幅度与网格离散化本身的不确定度同量级」这条不变量还成立；到 4.0 就跨过了
     * 半步长，等于允许把点挪到一个可能属于相邻网格单元的位置，会开始改路面走向（那是 H-02
     * 的激进一半，用户未批）。</p>
     *
     * <p><b>为什么桥不受影响</b>：桥段本身由 {@code straightenBridgeRuns} 拉直、且
     * {@code frozenByBridge} 让桥段与紧邻桥的点<b>完全不参与平滑</b>；桥头外侧 2~3 点走
     * 独立的 {@link #RELAX_BRIDGE_HEAD_MAX_DISPLACEMENT} = 1.0。改这个常量<strong>只影响
     * 非桥、非桥头的普通点</strong>。</p>
     *
     * <p>端点是硬契约（{@code relax} 循环从 1 到 n-2），一寸都不动。</p>
     */
    static final double RELAX_MAX_DISPLACEMENT = 3.0D;

    /**
     * 桥头点的累计横向位移上限（格）。
     *
     * <p>桥面本身由 {@code straightenBridgeRuns} 拉直、桥段与紧邻桥的点完全不参与平滑
     * （否则桥面端点会和路面错位）；这里只放宽到「桥头外侧 2~3 个点」做过渡，
     * 并用更严的 1 格上限保证接缝不错位。</p>
     */
    static final double RELAX_BRIDGE_HEAD_MAX_DISPLACEMENT = 1.0D;

    /**
     * 桥头"引道带"的站数半径（0.9.027 / V-4 甲）。
     *
     * <p>0.9.025 把全局上限 2.0 提到 3.0 时，桥段冻结、桥头 ±2/±3 仍是 1.0，但**4 站以外**
     * 的引道一下多挪了 1 格 —— 真机上就是桥头接缝处路面明显错位（V-4）。现在按距离分三档：
     * ±2/±3 = 1.0、±4..±8 = 2.0（0.9.025 之前的值）、更远 = 3.0。
     */
    static final int RELAX_BRIDGE_APPROACH_STATIONS = 8;

    /** 桥头引道带（±4..±8 站）的位移上限。用户 2026-11-08 选定「桥头 ±8 站回 2.0」。 */
    static final double RELAX_BRIDGE_APPROACH_MAX_DISPLACEMENT = 2.0D;

    /**
     * 0.9.067（C2）：急弯冻结阈值（度）。发卡弯顶点 / 90° 直角弯的转向角超过它就
     * 完全不参与 1-2-1 平滑 —— 否则会把弯心磨圆（盘山路的回头线被当成抖动拉直）。
     * 缓弯照常平滑。纯几何，与桥掩码正交。
     */
    static final double SHARP_TURN_FREEZE_DEG = 60.0D;

    static List<BlockPos> relaxPath(List<BlockPos> nodes) {
        return relax(nodes, null);
    }

    static List<BlockPos> relaxPathSkippingBridge(List<BlockPos> nodes, boolean[] bridgeMask) {
        return relax(nodes, bridgeMask);
    }

    /*
     * 多轮 1-2-1 加权平滑，带逐点累计位移上限。
     *
     * <p>端点、桥段点、以及紧邻桥的点一律不动（保持桥面直线与端点对齐）；桥头外侧
     * 2~3 个点参与平滑但上限收紧到 {@link #RELAX_BRIDGE_HEAD_MAX_DISPLACEMENT}。
     * Y 完全不动 —— 纵断面由 {@code RoadGradePlanner} 单独负责。</p>
     */
    /** 包级可见：供 {@code RelaxCapInvariantTest} 直接断言位移上限是严格不变量。 */
    static List<BlockPos> relax(List<BlockPos> nodes, boolean[] bridgeMask) {
        int n = nodes == null ? 0 : nodes.size();
        if (n < 3) return nodes == null ? new ArrayList<>() : new ArrayList<>(nodes);

        int[] x = new int[n], y = new int[n], z = new int[n];
        for (int i = 0; i < n; i++) {
            BlockPos p = nodes.get(i);
            x[i] = p.getX();
            y[i] = p.getY();
            z[i] = p.getZ();
        }
        int[] originX = x.clone(), originZ = z.clone();

        // 0.9.067（C2）：急弯冻结。发卡弯顶点（转向角 > SHARP_TURN_FREEZE_DEG）不参与平滑，
        // 否则 1-2-1 会把回头线的弯心磨圆。只在首轮前算一次（originX/Z 已固定，几何不变）。
        boolean[] sharpTurn = new boolean[n];
        for (int i = 1; i < n - 1; i++) {
            if (frozenByBridge(bridgeMask, i)) continue;
            sharpTurn[i] = turningAngleDeg(x[i - 1], z[i - 1], x[i], z[i], x[i + 1], z[i + 1])
                    >= SHARP_TURN_FREEZE_DEG;
        }

        for (int pass = 0; pass < RELAX_PASSES; pass++) {
            int[] nextX = x.clone(), nextZ = z.clone();
            boolean moved = false;
            for (int i = 1; i < n - 1; i++) {
                if (frozenByBridge(bridgeMask, i)) continue;
                if (sharpTurn[i]) continue;   // 0.9.067（C2）：急弯冻结
                // 0.9.019（O-05）：本行原来是整数除法 `(a + 2b + c) / 4`。Java 的 /
                // 是「向零截断」而不是向下取整，负坐标下会系统性偏正（-1/4 得 0、
                // 向下取整应得 -1）⇒ 整条西/北向的路会整体偏一格。改成真正的四舍五入。
                int smoothedX = (int) Math.round((x[i - 1] + 2.0D * x[i] + x[i + 1]) / 4.0D);
                int smoothedZ = (int) Math.round((z[i - 1] + 2.0D * z[i] + z[i + 1]) / 4.0D);
                double cap = bridgeHeadCap(bridgeMask, i);
                double dx = (double) smoothedX - originX[i];
                double dz = (double) smoothedZ - originZ[i];
                double distance = Math.hypot(dx, dz);
                if (distance > cap) {
                    double scale = cap / distance;
                    smoothedX = (int) Math.round(originX[i] + dx * scale);
                    smoothedZ = (int) Math.round(originZ[i] + dz * scale);
                }
                // 0.9.019（O-05）：上面的 Math.round 每个轴都可能多走 0.5 格，
                // 于是取整后的实际位移可以超出 cap（实测 2.24 / 1.24 格，而 cap 是
                // 2.0 / 1.0）。这里把 cap 变成**严格不变量**：超了就沿位移较大的
                // 那个轴一次退回一格，最多退 2 步就必进界（每步减少 ≥0.5 格）。
                double roundedDx = (double) smoothedX - originX[i];
                double roundedDz = (double) smoothedZ - originZ[i];
                for (int retract = 0; retract < 2
                        && Math.hypot(roundedDx, roundedDz) > cap; retract++) {
                    if (Math.abs(roundedDx) >= Math.abs(roundedDz)) {
                        smoothedX -= Integer.signum(smoothedX - originX[i]);
                    } else {
                        smoothedZ -= Integer.signum(smoothedZ - originZ[i]);
                    }
                    roundedDx = (double) smoothedX - originX[i];
                    roundedDz = (double) smoothedZ - originZ[i];
                }
                // `moved` 比的是本轮的输入（x/z 尚未写回），语义正确，本批不动。
                if (smoothedX != x[i] || smoothedZ != z[i]) moved = true;
                nextX[i] = smoothedX;
                nextZ[i] = smoothedZ;
            }
            x = nextX;
            z = nextZ;
            if (!moved) break;
        }

        List<BlockPos> relaxed = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            relaxed.add(new BlockPos(x[i], y[i], z[i]));
        }
        return relaxed;
    }

    /**
     * 0.9.067（C2）：三点转向角（度）。0=直行、180=完全折返。
     * 纯几何，无世界访问；三点共线或相邻点重合时返回 0（不判急弯）。
     */
    static double turningAngleDeg(int ax, int az, int bx, int bz, int cx, int cz) {
        double v1x = bx - ax, v1z = bz - az;
        double v2x = cx - bx, v2z = cz - bz;
        double len1 = Math.hypot(v1x, v1z);
        double len2 = Math.hypot(v2x, v2z);
        if (len1 < 1.0E-9D || len2 < 1.0E-9D) return 0.0D;
        double cos = (v1x * v2x + v1z * v2z) / (len1 * len2);
        cos = Math.max(-1.0D, Math.min(1.0D, cos));
        return Math.toDegrees(Math.acos(cos));
    }

    /** 桥段本身与紧邻桥的点完全不参与平滑，保证桥面直线与端点对齐。 */
    static boolean frozenByBridge(boolean[] bridgeMask, int index) {
        if (bridgeMask == null) return false;
        return isBridgeAt(bridgeMask, index)
                || isBridgeAt(bridgeMask, index - 1)
                || isBridgeAt(bridgeMask, index + 1);
    }

    /** 桥头外侧 2~3 格：参与平滑，但位移上限更严。包级可见供上限不变量测试核验。 */
    static double bridgeHeadCap(boolean[] bridgeMask, int index) {
        if (bridgeMask == null) return RELAX_MAX_DISPLACEMENT;
        if (isBridgeAt(bridgeMask, index - 2) || isBridgeAt(bridgeMask, index + 2)
                || isBridgeAt(bridgeMask, index - 3) || isBridgeAt(bridgeMask, index + 3)) {
            return RELAX_BRIDGE_HEAD_MAX_DISPLACEMENT;
        }
        // V-4 甲（0.9.027）：桥头 ±4..±8 站的引道回到 0.9.025 之前的 2.0。
        for (int d = 4; d <= RELAX_BRIDGE_APPROACH_STATIONS; d++) {
            if (isBridgeAt(bridgeMask, index - d) || isBridgeAt(bridgeMask, index + d)) {
                return RELAX_BRIDGE_APPROACH_MAX_DISPLACEMENT;
            }
        }
        return RELAX_MAX_DISPLACEMENT;
    }

    static boolean isBridgeAt(boolean[] bridgeMask, int index) {
        return index >= 0 && index < bridgeMask.length && bridgeMask[index];
    }
}
