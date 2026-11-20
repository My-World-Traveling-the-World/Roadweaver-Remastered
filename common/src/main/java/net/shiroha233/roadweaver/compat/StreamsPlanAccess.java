/* 文件职责：隔离河流可选实现的读计划与预热边界，避免主类强链接可选契约。 */
package net.shiroha233.roadweaver.compat;

import java.util.List;
import net.minecraft.server.level.ServerLevel;

/** 可选提供方的最小边界；主兼容类不链接河流API，缺模组仍能正常加载。 */
interface StreamsPlanAccess {
    Object open(ServerLevel level) throws Exception;
    default Object identity(Object session) { return session; }
    StreamsReflowingCompat.RiverColumn column(Object session, int x, int z) throws Exception;
    StreamsReflowingCompat.LakeColumn lake(Object session, int x, int z) throws Exception;
    List<?> platesNear(Object session, double x, double z, double radius) throws Exception;
    void prewarm(Object session, Object plate) throws Exception;
}
