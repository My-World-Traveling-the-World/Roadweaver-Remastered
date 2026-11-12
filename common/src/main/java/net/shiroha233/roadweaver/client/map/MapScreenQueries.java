/* 文件职责：地图界面要问世界的几个只读问题 —— 采样会话、自动化边界、原版出生点。 */
package net.shiroha233.roadweaver.client.map;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import net.shiroha233.roadweaver.client.map.data.MapAutomaticPlanningSamplingCache;
import net.shiroha233.roadweaver.planning.terrain.AutomaticPlanningSamplingActivities;
import net.shiroha233.roadweaver.planning.terrain.AutomaticPlanningSamplingBounds;
import net.shiroha233.roadweaver.planning.terrain.TerrainSamplingSessionSnapshot;
import net.shiroha233.roadweaver.planning.terrain.TerrainSamplingSessions;

/**
 * 地图界面对世界的只读查询。
 *
 * <p>全是「问一句、拿一个值」的短路查询，没有任何副作用。
 * 拆出来的好处是界面类不用再惦记这些 null 检查的组合。</p>
 */
public final class MapScreenQueries {

    private MapScreenQueries() {}

    /** 当前单机世界的地形采样会话快照；非单机或未开始采样时为 null。 */
    public static TerrainSamplingSessionSnapshot terrainSamplingSession(Minecraft minecraft) {
        if (minecraft == null) return null;
        ServerLevel level = MapViewportController.resolveSingleplayerLevel(minecraft);
        if (level == null) return null;
        return TerrainSamplingSessions.forLevel(level).snapshot();
    }

    /**
     * 自动化规划的采样边界。
     *
     * <p>单机按世界实时取；非单机取客户端缓存的那份 —— 否则联机时这块永远为空，
     * 玩家看不到别人正在规划哪里。</p>
     */
    public static List<AutomaticPlanningSamplingBounds> automaticPlanningBounds(
            ServerLevel level, ResourceLocation currentDimensionId) {
        if (level != null) {
            return AutomaticPlanningSamplingActivities.snapshot(level);
        }
        return MapAutomaticPlanningSamplingCache.snapshot(currentDimensionId);
    }

    /**
     * 原版出生点。
     *
     * <p>先问单机服务端（权威值），再退回客户端世界（联机只有这个）。
     * 都拿不到就返回 null，由调用方决定怎么处理。</p>
     */
    public static BlockPos resolveSpawn(Minecraft mc) {
        if (mc == null) return null;
        BlockPos spawn = null;
        MinecraftServer server = mc.getSingleplayerServer();
        if (server != null) {
            ServerLevel level = server.getLevel(Level.OVERWORLD);
            if (level != null) {
                spawn = level.getSharedSpawnPos();
            }
        }
        if (spawn == null && mc.level != null) {
            spawn = mc.level.getSharedSpawnPos();
        }
        return spawn;
    }
}
