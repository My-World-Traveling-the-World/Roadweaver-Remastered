/* 文件职责：告示牌方块实体注入：路面上的告示牌内容/更新保护。 */
package net.shiroha233.roadweaver.mixin.fabric;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.core.HolderLookup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 原生告示牌codec解码前修正畸形四行文本，避免整块内容因错误文本被拒。 */
@Mixin(SignBlockEntity.class)
public abstract class SignBlockEntityMixin {
    @Inject(method = "loadAdditional", at = @At("HEAD"))
    private void roadweaver$sanitizeMalformedSignText(CompoundTag tag,
                                                       HolderLookup.Provider registries,
                                                       CallbackInfo ci) {
        sanitizeSide(tag, "front_text");
        sanitizeSide(tag, "back_text");
    }

    private static void sanitizeSide(CompoundTag root, String key) {
        if (root == null || !root.contains(key, CompoundTag.TAG_COMPOUND)) return;
        CompoundTag text = root.getCompound(key);
        if (!text.contains("messages", ListTag.TAG_LIST)
                || text.getList("messages", StringTag.TAG_STRING).size() != 4) {
            ListTag messages = new ListTag();
            for (int line = 0; line < 4; line++) messages.add(StringTag.valueOf("\"\""));
            text.put("messages", messages);
        }
        if (text.contains("filtered_messages")
                && (!text.contains("filtered_messages", ListTag.TAG_LIST)
                || text.getList("filtered_messages", StringTag.TAG_STRING).size() != 4)) {
            text.remove("filtered_messages");
        }
        root.put(key, text);
    }
}
