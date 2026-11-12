/* 文件职责：结构选择界面左侧的模组导航栏。 */
package net.shiroha233.roadweaver.client.config;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.function.Consumer;

/** Left-hand mod navigator for the structure selection screen. */
public final class ModPickerListWidget extends RoadWeaverSelectionList<ModPickerListWidget.Entry> {
    private static final int ROW_HEIGHT = 28;

    public ModPickerListWidget(Minecraft minecraft, int width, int height, int top) {
        super(minecraft, width, height, top, ROW_HEIGHT, 18);
        setBackgroundColor(0xAA101216);
    }

    public void clearEntries() {
        super.clearEntries();
    }

    public int doAddEntry(Entry entry) {
        return super.addEntry(entry);
    }

    public abstract static class Entry extends ContainerObjectSelectionList.Entry<Entry> {
        protected final ModPickerListWidget list;

        protected Entry(ModPickerListWidget list) {
            this.list = list;
        }
    }

    public static final class ModEntry extends Entry {
        private static final int CHECKBOX_SIZE = 10;
        private final String modId;
        private final Component title;
        private final int selectedCount;
        private final int totalCount;
        private final boolean allEnabled;
        private final boolean partialEnabled;
        private final boolean selected;
        private final Consumer<String> onSelect;
        private final Consumer<String> onToggle;

        public ModEntry(ModPickerListWidget list, String modId, Component title,
                        int selectedCount, int totalCount, boolean allEnabled,
                        boolean partialEnabled, boolean selected,
                        Consumer<String> onSelect, Consumer<String> onToggle) {
            super(list);
            this.modId = modId;
            this.title = title;
            this.selectedCount = selectedCount;
            this.totalCount = totalCount;
            this.allEnabled = allEnabled;
            this.partialEnabled = partialEnabled;
            this.selected = selected;
            this.onSelect = onSelect;
            this.onToggle = onToggle;
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int width, int height,
                           int mouseX, int mouseY, boolean hovering, float partialTick) {
            Minecraft mc = Minecraft.getInstance();
            if (selected) {
                graphics.fill(left, top, left + width, top + height, 0x553F6B8A);
                graphics.fill(left, top, left + 2, top + height, 0xFF55AAFF);
            }

            int boxX = left + 6;
            int boxY = top + 9;
            graphics.fill(boxX, boxY, boxX + CHECKBOX_SIZE, boxY + CHECKBOX_SIZE, 0xFF000000);
            graphics.fill(boxX + 1, boxY + 1, boxX + CHECKBOX_SIZE - 1, boxY + CHECKBOX_SIZE - 1, 0xFF888888);
            if (allEnabled) {
                graphics.fill(boxX + 2, boxY + 2, boxX + CHECKBOX_SIZE - 2, boxY + CHECKBOX_SIZE - 2, 0xFF55FF55);
            } else if (partialEnabled) {
                graphics.fill(boxX + 3, boxY + 3, boxX + CHECKBOX_SIZE - 3, boxY + CHECKBOX_SIZE - 3, 0xFFFFFF55);
            }

            int textX = left + 22;
            ResourceLocation icon = StructureListWidget.getModIconTexture(modId);
            if (icon != null) {
                RenderSystem.enableBlend();
                graphics.blit(icon, textX, top + 6, 0, 0, 16, 16, 16, 16);
                RenderSystem.disableBlend();
                textX += 18;
            }

            int color = selected ? 0xFFFFFF : 0xDDDDDD;
            graphics.drawString(mc.font, title, textX, top + 5, color, false);
            String count = selectedCount + "/" + totalCount;
            graphics.drawString(mc.font, count, textX, top + 15, 0x888888, false);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button == 0) {
                int rowLeft = list.getRowLeft();
                if (mouseX >= rowLeft + 4 && mouseX <= rowLeft + 18) {
                    onToggle.accept(modId);
                } else if (mouseX >= rowLeft && mouseX <= rowLeft + list.getRowWidth()) {
                    onSelect.accept(modId);
                } else {
                    return super.mouseClicked(mouseX, mouseY, button);
                }
                return true;
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of();
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of();
        }
    }
}
