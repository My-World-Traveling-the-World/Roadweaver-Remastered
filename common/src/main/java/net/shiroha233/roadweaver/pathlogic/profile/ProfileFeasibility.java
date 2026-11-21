/* 文件职责：传播一阶、隔站坡限，证明窗口可行后才发布确定性修复剖面。 */
package net.shiroha233.roadweaver.pathlogic.profile;

import java.util.Optional;

/** 将坡度约束视作带权邻接图，避免固定轮数夹逼停在仍有跳变的结果上。 */
public final class ProfileFeasibility {
    private ProfileFeasibility() {}

    /** 求不超过各站上界的最高合规包络；生产隔站限值不小于单站限值，只需两次线性扫描。 */
    public static int[] upperEnvelope(int[] upper, int step, int perTwo) {
        if (step < 0) throw new IllegalArgumentException("单站坡限不能为负");
        var result = upper.clone();
        // 与现有 DP 使用同一高差域：隔站限值为0代表平路，首对高度也不能绕过它。
        if (perTwo >= 0) step = Math.min(step, perTwo);
        for (int i = 1; i < result.length; i++) {
            result[i] = Math.min(result[i], plus(result[i - 1], step));
            if (i >= 2 && perTwo >= 0) result[i] = Math.min(result[i], plus(result[i - 2], perTwo));
        }
        for (int i = result.length - 2; i >= 0; i--) {
            result[i] = Math.min(result[i], plus(result[i + 1], step));
            if (i + 2 < result.length && perTwo >= 0) result[i] = Math.min(result[i], plus(result[i + 2], perTwo));
        }
        return result;
    }

    /** 下界必须先向两侧传播；直接下压地形会误判本可由填方接坡的窗口无解。 */
    public static Optional<int[]> project(int[] terrain, int[] lower, int[] upper, int step, int perTwo) {
        if (terrain.length != lower.length || terrain.length != upper.length) {
            throw new IllegalArgumentException("站数必须一致");
        }
        var negated = new int[lower.length];
        for (int i = 0; i < lower.length; i++) negated[i] = Math.negateExact(lower[i]);
        var propagated = upperEnvelope(negated, step, perTwo);
        var preferred = new int[terrain.length];
        for (int i = 0; i < preferred.length; i++) {
            int floor = Math.negateExact(propagated[i]);
            if (floor > upper[i]) return Optional.empty();
            preferred[i] = Math.max(floor, Math.min(upper[i], terrain[i]));
        }
        var profile = upperEnvelope(preferred, step, perTwo);
        for (int i = 0; i < profile.length; i++) {
            if (profile[i] < lower[i] || profile[i] > upper[i]) return Optional.empty();
        }
        return Optional.of(profile);
    }

    /** 高度加坡限用饱和加法，避免极端输入整数溢出变成深坑。 */
    private static int plus(int value, int delta) {
        return (int) Math.min(Integer.MAX_VALUE, (long) value + delta);
    }
}
