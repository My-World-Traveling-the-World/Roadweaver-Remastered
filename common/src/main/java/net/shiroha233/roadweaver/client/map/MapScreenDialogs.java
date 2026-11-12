/* 文件职责：地图界面弹出的对话框与右键菜单 —— 只负责「开哪个界面」，不含业务判断。 */
package net.shiroha233.roadweaver.client.map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import net.shiroha233.roadweaver.client.map.data.ClientMapNotes;
import net.shiroha233.roadweaver.client.map.ui.ContextMenu;
import net.shiroha233.roadweaver.client.map.ui.NoteEditScreen;
import net.shiroha233.roadweaver.client.map.ui.SimpleTextInputScreen;

/**
 * 地图界面的对话框与右键菜单。
 *
 * <h2>为什么单独一个类</h2>
 * <p>这三段代码原本长在 1083 行的 {@code RoadMapScreen} 里，做的事只有一件：
 * <b>把哪个界面推上去</b>。它们不参与地图渲染、不碰快照、不改任何状态 ——
 * 和数据与绘制都没有耦合，留在界面类里只会让那个类继续膨胀。</p>
 *
 * <h2>parent 一律传地图界面</h2>
 * <p>对话框关闭后要回到地图而不是主菜单，所以 {@code parent} 显式传入而不是取
 * {@code Minecraft.screen} —— 后者在对话框建好的瞬间可能已经是对话框自己了。</p>
 */
public final class MapScreenDialogs {

    private MapScreenDialogs() {}

    /**
     * 打开某个坐标的右键菜单。
     *
     * @param onTeleport 点「传送」时执行
     * @param onAlias    点「设置别名」时执行
     * @param onNote     点「编辑笔记」时执行
     */
    public static void openContextMenu(ContextMenu menu, int x, int y,
                                       Component teleportLabel, Component aliasLabel, Component noteLabel,
                                       Runnable onTeleport, Runnable onAlias, Runnable onNote) {
        menu.clearItems();
        menu.addItem(teleportLabel, onTeleport);
        menu.addSeparator();
        menu.addItem(aliasLabel, onAlias);
        menu.addItem(noteLabel, onNote);
        menu.open(x, y);
    }

    /** 打开别名设置对话框（预填当前别名）。 */
    public static void openAliasEditor(Minecraft mc, Screen parent, BlockPos target, Component title) {
        if (mc == null) return;
        String currentAlias = ClientMapNotes.getAlias(target);
        mc.setScreen(new SimpleTextInputScreen(
                title,
                currentAlias != null ? currentAlias : "",
                alias -> ClientMapNotes.setAlias(target, alias),
                parent));
    }

    /** 打开笔记编辑器（书与笔风格）。 */
    public static void openNoteEditor(Minecraft mc, Screen parent, BlockPos target) {
        if (mc == null) return;
        mc.setScreen(new NoteEditScreen(target, parent));
    }
}
