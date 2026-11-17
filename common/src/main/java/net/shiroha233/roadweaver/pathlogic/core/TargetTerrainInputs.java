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
        // Structure connections keep endpoint Y flattened to zero. Include
        // the actual center columns in the same accurate sampling batch so
        // the grade planner can anchor the profile to terrain at both ends.
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
            // The world read is this station's last-resort fallback, only reached when the accurate backend
            // missed the centre sample or the segment carries no positions at all. It used to run for every
            // station unconditionally, which put a live block read (up to 129 getBlockState calls, see
            // RoadConstructionSafety.terrainHeight) inside the planning loop - while the gate parks the
            // server thread without pumping the chunk queue, that read can wait on a chunk nobody generates.
            // Guarding it changes no value: both consumers below are already conditional on exactly these
            // two cases.
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
            // Never use the flattened connection Y as a terrain fallback. A
            // width-support sample is safer than treating the terrain as Y=0
            // when a backend temporarily misses one center sample.
            int widthSupport = stationTerrain.length == 0
                    ? worldCenterTerrain
                    : RoadGradePlanner.robustFootprintSupport(stationTerrain);
            terrainY[i] = centerSample == null
                    ? widthSupport
                    : surfaceHeight.applyAsInt(centerSample);
            footprintSupport[i] = stationTerrain.length == 0
                    ? terrainY[i] : RoadGradePlanner.robustFootprintSupport(stationTerrain);
        }
        for (int i = 0; i < n; i++) {
            throwIfInterrupted();
            base[i] = RoadGradePlanner.robustWindowHeight(terrainY, i, avg);
        }

        return new Input(terrainY, footprintSupport, base, footprintSamples);
    }
}
