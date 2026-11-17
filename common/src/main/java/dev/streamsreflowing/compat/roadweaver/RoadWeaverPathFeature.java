/* 文件职责：把道路的顶层施工 feature 从 Streams Reflowing 的通用植被否决里排除（Streams 靠 feature 类型识别自家兼容项）。 */
package dev.streamsreflowing.compat.roadweaver;

import com.mojang.serialization.Codec;
import net.shiroha233.roadweaver.pathlogic.PathFeature;
import net.shiroha233.roadweaver.config.PathFeatureConfig;

/** 使用河流兼容包名保留道路顶层施工，避免被河流通用植被否决误拦；不要随意迁移此类包名。
 *
 * Keeps RoadWeaver's top-layer construction feature out of Streams Reflowing's
 * generic vegetation veto. Streams identifies its own compatible features by
 * package name before consulting the river veto probe.
 */
public final class RoadWeaverPathFeature extends PathFeature {
    public RoadWeaverPathFeature(Codec<PathFeatureConfig> codec) {
        super(codec);
    }
}
