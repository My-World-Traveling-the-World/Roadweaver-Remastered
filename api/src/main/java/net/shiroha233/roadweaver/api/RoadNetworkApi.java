package net.shiroha233.roadweaver.api;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * 路网操作对外入口：登记结构端点、确保两点连通、规划矩形区域。
 *
 * <p>本类原本在 {@code common} 模块里直接 {@code import} 了 12 个内部实现类
 * （{@code RoadGenerationService} / {@code RoadSnapService} / {@code WorldDataProvider} …），
 * 是「契约与实现交织」的典型。搬到 api 模块后只做转发，
 * 实现挪到 {@link RoadWeaverBackend}。</p>
 *
 * <p>RoadWeaver 未安装时全部方法安全降级为空操作。</p>
 */
public final class RoadNetworkApi {

    private RoadNetworkApi() {
    }

    /** 登记一个结构端点（不自动连线）。 */
    public static void registerStructureEndpoint(ServerLevel level, BlockPos pos) {
        registerStructureEndpoint(level, pos, null, false);
    }

    /** 登记一个结构端点。 */
    public static void registerStructureEndpoint(ServerLevel level, BlockPos pos, boolean autoConnect) {
        registerStructureEndpoint(level, pos, null, autoConnect);
    }

    /**
     * 登记一个结构端点。
     *
     * @param structureId 结构标识，{@code null} 或空表示未知
     * @param autoConnect 是否自动与已有端点连线
     */
    public static void registerStructureEndpoint(ServerLevel level, BlockPos pos, String structureId,
                                                 boolean autoConnect) {
        RoadWeaverBackend b = RoadWeaverApi.backend();
        if (b == null) {
            return;
        }
        try {
            b.registerStructureEndpoint(level, pos, structureId, autoConnect);
        } catch (RuntimeException | LinkageError ignored) {
        }
    }

    /** 确保两点之间存在连线（已存在则跳过）。 */
    public static void ensureConnection(ServerLevel level, BlockPos from, BlockPos to) {
        ensureConnection(level, from, to, false);
    }

    /**
     * 确保两点之间存在连线。
     *
     * @param generateImmediately {@code true} 表示同步立即生成道路
     */
    public static void ensureConnection(ServerLevel level, BlockPos from, BlockPos to,
                                        boolean generateImmediately) {
        RoadWeaverBackend b = RoadWeaverApi.backend();
        if (b == null) {
            return;
        }
        try {
            b.ensureConnection(level, from, to, generateImmediately);
        } catch (RuntimeException | LinkageError ignored) {
        }
    }

    /** 异步规划一个矩形区域内的路网。 */
    public static void planRegion(ServerLevel level, int minBlockX, int minBlockZ,
                                  int maxBlockX, int maxBlockZ) {
        RoadWeaverBackend b = RoadWeaverApi.backend();
        if (b == null) {
            return;
        }
        try {
            b.planRegion(level, minBlockX, minBlockZ, maxBlockX, maxBlockZ);
        } catch (RuntimeException | LinkageError ignored) {
        }
    }
}
