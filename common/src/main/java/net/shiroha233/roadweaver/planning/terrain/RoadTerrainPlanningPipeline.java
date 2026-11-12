/* 文件职责：按世界采样会话选择道路地形规划适配器，并收口全区域模式的安全降级。 */
package net.shiroha233.roadweaver.planning.terrain;

import net.shiroha233.roadweaver.config.sub.TerrainSamplingMode;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSampler;
import net.shiroha233.roadweaver.generation.progress.InitialGenerationProgressTracker;

import java.util.Objects;

/**
 * 道路地形规划用例入口。
 */
public final class RoadTerrainPlanningPipeline {
    private final RoadTerrainPlanningPort coarseCorridor;
    private final RoadTerrainPlanningPort fullRegion;
    private final RoadTerrainPlanningPort legacyDirect;

    public RoadTerrainPlanningPipeline() {
        this(new CoarseCorridorPlanningAdapter(), new FullRegionPlanningAdapter(), new LegacyDirectPlanningAdapter());
    }

    RoadTerrainPlanningPipeline(RoadTerrainPlanningPort coarseCorridor,
                                RoadTerrainPlanningPort fullRegion,
                                RoadTerrainPlanningPort legacyDirect) {
        this.coarseCorridor = Objects.requireNonNull(coarseCorridor, "coarseCorridor");
        this.fullRegion = Objects.requireNonNull(fullRegion, "fullRegion");
        this.legacyDirect = Objects.requireNonNull(legacyDirect, "legacyDirect");
    }

    public RoadTerrainPlanningPort.Result plan(RoadTerrainPlanningPort.Request request) {
        Objects.requireNonNull(request, "request");
        TerrainSamplingSession session = TerrainSamplingSessions.forLevel(request.level());
        if (session.effectiveMode() == TerrainSamplingMode.LEGACY_DIRECT) {
            return legacyDirect.plan(request);
        }
        AccurateHeightSampler sampler = AccurateHeightSampler.create(request.level());
        session.recordBackend(sampler.backendName(), sampler.deviceName());
        InitialGenerationProgressTracker.setBackend(sampler.backendName(), sampler.deviceName(), "");

        if (request.hasCircularSamplingMask()) {
            try {
                return fullRegion.plan(request);
            } catch (FullRegionUnavailableException unavailable) {
                // A local repair is strictly conflict-centered. Falling back
                // to the corridor adapter here would discard the circular
                // mask and rescan the entire endpoint-to-endpoint rectangle.
                // Refuse that broad fallback; the caller can move the route
                // to its bounded candidate phase instead.
                InitialGenerationProgressTracker.setBackend(
                        sampler.backendName(), sampler.deviceName(), fallbackReason(unavailable));
                throw unavailable;
            }
        }
        if (session.effectiveMode() != TerrainSamplingMode.FULL_REGION) {
            return planCorridor(request, session, sampler);
        }

        try {
            RoadTerrainPlanningPort.Result result = fullRegion.plan(request);
            session.recordBackend(sampler.backendName(), sampler.deviceName());
            return session.effectiveMode() == TerrainSamplingMode.FULL_REGION
                    ? result
                    : planCorridor(request, session, sampler);
        } catch (FullRegionUnavailableException unavailable) {
            String reason = fallbackReason(unavailable);
            session.downgrade(reason, sampler.backendName(), sampler.deviceName());
            InitialGenerationProgressTracker.setBackend(sampler.backendName(), sampler.deviceName(), reason);
            return planCorridor(request, session, sampler);
        }
    }

    private RoadTerrainPlanningPort.Result planCorridor(RoadTerrainPlanningPort.Request request,
                                                        TerrainSamplingSession session,
                                                        AccurateHeightSampler sampler) {
        RoadTerrainPlanningPort.Result result = coarseCorridor.plan(request);
        session.recordBackend(sampler.backendName(), sampler.deviceName());
        return result;
    }

    private static String fallbackReason(FullRegionUnavailableException unavailable) {
        String message = unavailable.getMessage();
        if (message != null && !message.isBlank()) {
            return message;
        }
        return "full-region FP64 GPU sampling unavailable";
    }
}
