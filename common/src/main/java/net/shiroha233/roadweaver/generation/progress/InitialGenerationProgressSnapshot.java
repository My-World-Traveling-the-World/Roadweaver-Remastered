/* 文件职责：承载初始生成各阶段供客户端只读展示的进度快照。 */
package net.shiroha233.roadweaver.generation.progress;

/**
 * 初始生成进度快照，供客户端加载界面只读展示。
 */
public record InitialGenerationProgressSnapshot(
        boolean active,
        InitialGenerationStage stage,
        String stageLabel,
        long startedAtMillis,
        long elapsedMillis,
        int overallPercent,
        int stagePercent,
        int connectionsTotal,
        int connectionsGenerating,
        int connectionsDone,
        int connectionsFailed,
        int connectionsLocalSampling,
        int connectionsFallbackCandidates,
        int planningTilesTotal,
        int planningTilesDone,
        int planningStartsTotal,
        int planningStartsDone,
        int planningEmptyStarts,
        int tilesTotal,
        int tilesLoaded,
        int tilesSampled,
        int tilesFromMemory,
        int tilesFromDisk,
        long samplesTotal,
        long samplesDone,
        int exactPathsTotal,
        int exactPathsDone,
        int constructionChunksTotal,
        int constructionChunksDone,
        double samplesPerSecond,
        int lastBatchSamples,
        long lastBatchMillis,
        String backend,
        String deviceName,
        String devicePreference,
        String fallbackReason,
        int initialThreads,
        int activeWorkers,
        int currentSamplingStep,
        String currentOperation,
        int terrainCacheHitPercent,
        long terrainNoiseSamplesTotal,
        double terrainNoiseSamplesPerSec,
        int accurateCacheHitPercent,
        long accurateBaseHeightTotal,
        double accurateBaseHeightPerSec,
        RoutePhaseProgress originalRoutes,
        RoutePhaseProgress localRepairRoutes,
        RoutePhaseProgress candidateRoutes,
        int planningRound,
        java.util.List<InitialGenerationStage> visitedStages
) {
    /** 提供当前阶段的可读子进度，让界面区分等待和实际工作。 */
    public String subProgressLabel() {
        if ("gui.roadweaver.initgen.stage.coarse_topology".equals(stageLabel)) {
            // 这是已收集的数据量，不是选边器的虚假剩余进度。
            return "已收集端点 " + planningStartsDone;
        }
        // 走廊检查沿用路径主阶段，却应展示结构计数，不能继续显示已完成的路径数。
        if ("gui.roadweaver.initgen.stage.corridor_check".equals(stageLabel)) {
            return planningStartsTotal > 0 ? "结构 " + planningStartsDone + "/" + planningStartsTotal : "";
        }
        return switch (stage) {
            case PLANNING -> planningStartsTotal > 0
                    ? "结构 " + planningStartsDone + "/" + planningStartsTotal
                    : (planningTilesTotal > 0 ? "瓦片 " + planningTilesDone + "/" + planningTilesTotal : "");
            case EXACT_SAMPLING -> samplesTotal > 0 ? "采样 " + samplesDone + "/" + samplesTotal : "";
            case EXACT_PATHING -> exactPathsTotal > 0 ? "路径 " + exactPathsDone + "/" + exactPathsTotal : "";
            case COARSE_SAMPLING -> tilesTotal > 0 ? "瓦片 " + tilesLoaded + "/" + tilesTotal : "";
            case COARSE_PATHING -> exactPathsTotal > 0 ? "路径 " + exactPathsDone + "/" + exactPathsTotal : "";
            case ROAD_GENERATION -> connectionsTotal > 0
                    ? "路线 " + connectionsDone + "/" + connectionsTotal
                    : "";
            case POST_PROCESSING -> constructionChunksTotal > 0
                    ? "最终道路 " + constructionChunksDone + "/" + constructionChunksTotal
                    : "";
            case FINISHED -> "";
        };
    }

    public record RoutePhaseProgress(int total, int generating, int done, int failed, int retained, int handedOff,
                                     String status, int samplingStep) {
        public static RoutePhaseProgress zero() {
            return new RoutePhaseProgress(0, 0, 0, 0, 0, 0, "idle", -1);
        }

        public int pending() {
            return Math.max(0, total - generating - done - failed - retained - handedOff);
        }
    }

    public static InitialGenerationProgressSnapshot inactive() {
        return new InitialGenerationProgressSnapshot(
                false,
                InitialGenerationStage.FINISHED,
                InitialGenerationStage.FINISHED.labelKey(),
                0L,
                0L,
                100,
                100,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0L,
                0L,
                0,
                0,
                0,
                0,
                0.0,
                0,
                0L,
                "-",
                "-",
                "-",
                "",
                0,
                0,
                -1,
                "",
                0,
                0L,
                0.0,
                0,
                0L,
                0.0,
                RoutePhaseProgress.zero(),
                RoutePhaseProgress.zero(),
                RoutePhaseProgress.zero(),
                0,
                java.util.List.of()
        );
    }
}
