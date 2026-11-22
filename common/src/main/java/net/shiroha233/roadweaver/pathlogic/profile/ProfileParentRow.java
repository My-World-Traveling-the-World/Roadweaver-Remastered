/* 文件职责：压缩二阶纵断面的回溯父表，同时保留更大坡度范围的直接调用。 */
package net.shiroha233.roadweaver.pathlogic.profile;

/** 二阶回溯时，前一站高度可由当前高度减当前高差还原，因此只保存前一站的高差序号。 */
final class ProfileParentRow {
    private final int firstState;
    private final byte[] small;
    private final int[] wide;

    ProfileParentRow(int firstState, int rowStates, int deltaCount) {
        this.firstState = firstState;
        // 0 表示未记录；配置范围用一字节，直接调用的更大范围仍走相同编码和转移。
        small = deltaCount <= 255 ? new byte[rowStates] : null;
        wide = small == null ? new int[rowStates] : null;
    }

    void put(int destination, int previousDeltaIndex) {
        int local = destination - firstState;
        int value = previousDeltaIndex + 1;
        if (small != null) small[local] = (byte) value;
        else wide[local] = value;
    }

    int previousDelta(int destination) {
        int local = destination - firstState;
        return (small != null ? Byte.toUnsignedInt(small[local]) : wide[local]) - 1;
    }
}
