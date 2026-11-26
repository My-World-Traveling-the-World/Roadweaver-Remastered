/* 文件职责：根据地图版本和旧桥接加载状态选择唯一的叠线入口。 */
package net.shiroha233.roadweaver.mixin.diorama;

import java.util.List;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** 只有匹配的地图发行版才登记注入，避免加载缺失或已改签名的客户端类。 */
public final class DioramaMixinPlugin implements IMixinConfigPlugin {
    @Override
    public List<String> getMixins() {
        var loader = FabricLoader.getInstance();
        String version = loader.getModContainer("diorama")
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                .orElse(null);
        return selectMixins(version, loader.isModLoaded("plaincraft_roadweaver_diorama"));
    }

    static List<String> selectMixins(String version, boolean legacyBridge) {
        // 升级过程中若旧桥接仍在，让它负责绘制，保证只执行一份查询和叠线。
        return !legacyBridge && "0.11.0+1.21-1.21.1".equals(version)
                ? List.of("DioramaMapScreenMixin") : List.of();
    }

    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String target, String mixin) { return true; }
    @Override public void acceptTargets(Set<String> targets, Set<String> others) {}
    @Override public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
    @Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
}
