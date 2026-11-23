/* 文件职责：按站序缓存长路的保守区块范围，精确相交和铺设归属仍由编译器判定。 */
package net.shiroha233.roadweaver.worldgen.road;

import java.util.Arrays;
import net.minecraft.world.level.ChunkPos;

final class RoadStationBounds {
    static final int GROUP = 32;
    private final int[] boxes;
    private final int stations;

    RoadStationBounds(int[] boxes, int stations) {
        this.boxes = boxes;
        this.stations = stations;
    }

    // null 表示全范围，小路不为一次顺序遍历分配对象；EMPTY 则证明没有候选。
    record Selection(int[] runs, int count) {
        private static final Selection EMPTY = new Selection(new int[0], 0);

        static int count(Selection value) { return value == null ? 1 : value.count; }
        static int start(Selection value, int run) { return value == null ? 0 : value.runs[2 * run]; }
        static int end(Selection value, int run, int limit) {
            return value == null ? limit : Math.min(limit, value.runs[2 * run + 1]);
        }
    }

    Selection find(ChunkPos chunk, int margin) {
        int x0 = chunk.getMinBlockX() - margin, z0 = chunk.getMinBlockZ() - margin;
        int x1 = chunk.getMaxBlockX() + margin, z1 = chunk.getMaxBlockZ() + margin;
        int[] runs = null;
        int used = 0;
        for (int offset = 0, first = 0; offset < boxes.length; offset += 4, first += GROUP) {
            if (boxes[offset] > x1 || boxes[offset + 1] > z1
                    || boxes[offset + 2] < x0 || boxes[offset + 3] < z0) continue;
            int end = Math.min(stations, first + GROUP);
            // 按原站序合并相邻有效组；交叠道路的等高裁决依赖原始顺序。
            if (used != 0 && runs[used - 1] == first) {
                runs[used - 1] = end;
            } else {
                if (runs == null) runs = new int[4];
                else if (used == runs.length) runs = Arrays.copyOf(runs, used * 2);
                runs[used++] = first;
                runs[used++] = end;
            }
        }
        if (used == 0) return Selection.EMPTY;
        return used == 2 && runs[0] == 0 && runs[1] == stations ? null : new Selection(runs, used / 2);
    }
}
