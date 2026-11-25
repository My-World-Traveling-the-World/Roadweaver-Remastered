/* 文件职责：组装纵断面地形输入，让世界读取与剖面编排可分别验证。 */
package net.shiroha233.roadweaver.pathlogic.core;

import java.util.*;
import java.util.function.*;
import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSample;
import net.shiroha233.roadweaver.pathlogic.grade.RoadGradePlanner;
import static net.shiroha233.roadweaver.pathlogic.core.RoadSpanProfileOps.throwIfInterrupted;

final class TargetTerrainInputs {
    private TargetTerrainInputs() {}
    record Input(int[] terrainY, int[] footprintSupport, int[] base,
                 Map<Long, AccurateHeightSample> footprintSamples) {}

    static Input sample(List<RoadSegmentPlacement> segments, List<BlockPos> centers, int avg,
                        Function<List<BlockPos>, Map<Long, AccurateHeightSample>> samples,
                        ToIntFunction<AccurateHeightSample> surfaceHeight,
                        ToIntFunction<BlockPos> worldTerrain) {
        // 一批同时取路面和中心；精确样本完整时不触碰实时世界，避免等待未加载区块。
        int n = segments.size();
        int[] terrainY = new int[n], footprintSupport = new int[n], base = new int[n];
        List<BlockPos> footprintPositions = new ArrayList<>();
        for (RoadSegmentPlacement segment : segments) {
            throwIfInterrupted();
            if (segment != null && segment.positions() != null) footprintPositions.addAll(segment.positions());
        }
        // 连接端点Y按身份扁平化为零，中心列必须与铺装列同批采样，才能锚定真实地形。
        footprintPositions.addAll(centers);
        throwIfInterrupted();
        Map<Long, AccurateHeightSample> footprintSamples = samples.apply(footprintPositions);
        throwIfInterrupted();
        for (int i = 0; i < n; i++) {
            throwIfInterrupted();
            BlockPos center = centers.get(i);
            AccurateHeightSample centerSample = center == null ? null
                    : footprintSamples.get(AccurateHeightSample.key(center.getX(), center.getZ()));
            RoadSegmentPlacement segment = segments.get(i);
            List<BlockPos> positions = segment == null ? List.of() : segment.positions();
            // 实时世界读只作中心缺样或空占地的回落；完整样本时不能等待无人推进的区块任务。
            boolean needsWorldTerrain = centerSample == null || positions == null || positions.isEmpty();
            int worldCenterTerrain = !needsWorldTerrain ? 0 : worldTerrain.applyAsInt(center);
            int[] stationTerrain = new int[positions == null ? 0 : positions.size()];
            for (int j = 0; j < stationTerrain.length; j++) {
                throwIfInterrupted();
                BlockPos position = positions.get(j);
                AccurateHeightSample sample = footprintSamples.get(
                        AccurateHeightSample.key(position.getX(), position.getZ()));
                stationTerrain[j] = sample == null
                        ? (centerSample == null ? worldCenterTerrain : surfaceHeight.applyAsInt(centerSample))
                        : surfaceHeight.applyAsInt(sample);
            }
            // 中心暂缺时优先宽度支撑样本，不能把身份端点的Y=0当成地形高度。
            int widthSupport = stationTerrain.length == 0
                    ? worldCenterTerrain
                    : RoadGradePlanner.robustFootprintSupport(stationTerrain);
            terrainY[i] = centerSample == null
                    ? widthSupport
                    : surfaceHeight.applyAsInt(centerSample);
            // 同一站的宽度样本未改变，复用已求出的支撑值；空占地仍以真实中心样本为准。
            footprintSupport[i] = stationTerrain.length == 0 ? terrainY[i] : widthSupport;
        }
        for (int i = 0; i < n; i++) {
            throwIfInterrupted();
            base[i] = RoadGradePlanner.robustWindowHeight(terrainY, i, avg);
        }

        return new Input(terrainY, footprintSupport, base, footprintSamples);
    }
}
