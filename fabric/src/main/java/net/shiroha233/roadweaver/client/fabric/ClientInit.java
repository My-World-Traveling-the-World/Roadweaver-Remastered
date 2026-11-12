package net.shiroha233.roadweaver.client.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.shiroha233.roadweaver.client.map.ClientMapAccessGuard;
import net.shiroha233.roadweaver.client.map.RoadMapScreen;
import net.shiroha233.roadweaver.client.map.data.ClientMapNotes;
import net.shiroha233.roadweaver.client.map.data.MapDataStorage;
import net.shiroha233.roadweaver.client.map.data.MapSnapshotCache;
import net.shiroha233.roadweaver.client.map.tile.ClientMapTileTextureCache;
import net.shiroha233.roadweaver.apiimpl.LoadSignalBridge;
import net.shiroha233.roadweaver.network.fabric.MapNetworkFabric;
import net.shiroha233.roadweaver.runtime.RuntimeLoadPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.lwjgl.glfw.GLFW;

/**
 * Fabric 客户端初始化
 */
public class ClientInit implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver/api");

    public static KeyMapping OPEN_MAP;

    /**
     * 世界内切换「手绘路线编辑模式」的按键。
     *
     * <p>用户要求「3D 世界里用按键，绑定个按键就行」——
     * 地图界面里是用 Dock 按钮进编辑，世界里就靠这个键。</p>
     */
    public static KeyMapping TOGGLE_ROUTE_EDIT;

    @Override
    public void onInitializeClient() {
        // ── 跨 mod 信号注册表：标题界面出现时打一行解析结果 ─────────────────
        //
        // 为什么放在 CLIENT_STARTED 而不是本方法里：
        // 各 mod 的 onInitialize 顺序不确定，RoadWeaver 可能先于提供方初始化，
        // 那样读到的是「还没人注册」。CLIENT_STARTED 在客户端完全起来（标题界面）
        // 之后才触发，此时**所有 mod 的 entrypoint 都已跑完**，结果才可信。
        ClientLifecycleEvents.CLIENT_STARTED.register(client ->
                LOGGER.info("RoadWeaver {}", LoadSignalBridge.describeResolution()));
        MapNetworkFabric.registerClientReceivers();

        // 世界内手绘路线渲染（线条 + 控制点句柄）。
        // 经 @ExpectPlatform 分派到 fabric 实现，common 模块看不到 fabric-api。
        net.shiroha233.roadweaver.route.editor.RouteWorldRendererBridge.registerWorldRender();

        // 手绘路线编辑器的全部按键（真正的 KeyMapping，玩家可在「选项 → 按键」里改）。
        // 同样经 @ExpectPlatform 分派 —— KeyBindingHelper 在 common 看不见。
        net.shiroha233.roadweaver.route.editor.RouteKeysBridge.registerAll();

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            ClientMapAccessGuard.reset();
            MapSnapshotCache.setCurrentWorldId(MapDataStorage.getWorldId());
            MapSnapshotCache.clearNow();
            ClientMapTileTextureCache.clear(client);
            ClientMapNotes.onWorldJoin();
            // ★ 审计 R3-P3-8：跨世界必须清掉世界内编辑器的撤销栈与选中项。
            // 原来只在「进出编辑模式」时 reset()，所以「编辑模式中退出世界 →
            // 进另一世界」（中间没切过编辑模式）时旧快照仍在，按一下 Z 就会
            // 把旧世界的路线复活到新世界。
            net.shiroha233.roadweaver.route.editor.RouteWorldEditor.reset();
            // 手绘路线：从存档载入（单人直接读；多人拿不到 ServerLevel 就跳过）
            net.shiroha233.roadweaver.route.editor.RouteEditorSession.get().loadFromWorld(client);
            // ★ 审计 R3-P1-4：用存档里的路线**重建服务端登记表**。
            //
            // 登记表（连接 → kind/高度）纯内存且从不持久化，而路径是持久化的 ——
            // 于是「提交后没建完就退出/重进世界」时路的位置还对（缓存命中），
            // 但桥隧语义全丢（routeFor 返回 null ⇒ 不加 patcher ⇒ 桥变贴地路）。
            // 玩家意图的权威副本本来就在 routes.json 里，所以这里重建索引即可。
            rebuildRouteRegistry(client);
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            // 离开世界前先把草稿落盘，否则「画完就退」的路线会丢
            net.shiroha233.roadweaver.route.editor.RouteEditorSession.get().saveToWorld(client);
            // ★ 审计 R3-P3-8：离开世界同样要清，避免下一世界继承旧撤销栈
            net.shiroha233.roadweaver.route.editor.RouteWorldEditor.reset();
            net.shiroha233.roadweaver.route.editor.RouteEditorSession.get().clearAll();
            // ★ Codex 审查 D-1（2026-09-25）：**服务端登记表也要清**。
            //
            // RouteEditorSession.clearAll() 只清「会话的路线表」，完全没碰
            // RouteBuildRegistry —— 于是它的四张按维度表（连接登记 / 按路线计数 /
            // 历史总数 / 路线本体）在 level 存活期间一直留着旧世界的数据。
            // 其中 ROUTES_BY_LEVEL 正是 Codex 报的「漏清表」。
            //
            // key 是 WeakHashMap 的弱引用 ServerLevel，level 被 GC 后分区会自动消失，
            // 所以不是永久泄漏；但「离开世界就清干净」才是方法名承诺的语义。
            //
            // ⚠️ client.level 是 ClientLevel，不能直接传 —— 从集成服务器取
            //    主世界的 ServerLevel。拿不到（如已断开/多人）就跳过，交给
            //    WeakHashMap 自然回收。
            net.shiroha233.roadweaver.route.RouteBuildRegistry.onWorldLeave(
                    resolveOverworldForCleanup(client));
            // ★ 2026-09-25 修 R-6：预加载去重标记也要清。
            //
            // 原先 RouteChunkPreloader.clearRuntime 只有定义、**没有调用点** ——
            // 断开时不清理，同一 level 对象被复用（或集成服务端未立刻回收）时，
            // 上一轮"已预加载"的标记会让新一轮直接跳过强制加载。
            // 与 RouteBuildRegistry.onWorldLeave 并列，保证"离开世界 = 全清"。
            net.shiroha233.roadweaver.route.RouteBuildService.clearPreloadState(
                    resolveOverworldForCleanup(client));
            // ★ 任务 C（2026-09-26）：手绘施工优先模式的状态也要清。
            //   否则同一 level 对象被复用时，"优先模式已开始"的时间戳会残留，
            //   下一轮的长时警告会算错时长。
            net.shiroha233.roadweaver.route.PlayerRoutePriority
                    .clearRuntimeFor(resolveOverworldForCleanup(client));
            ClientMapAccessGuard.reset();
            MapSnapshotCache.clearNow();
            MapSnapshotCache.setCurrentWorldId(null);
            ClientMapTileTextureCache.clear(client);
            ClientMapNotes.onWorldLeave();
        });

        OPEN_MAP = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.roadweaver.open_map",
                GLFW.GLFW_KEY_H,
                "key.categories.roadweaver"
        ));

        // 世界内切换手绘路线编辑模式（默认 J，与 H 开地图区分开）
        TOGGLE_ROUTE_EDIT = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.roadweaver.toggle_route_edit",
                GLFW.GLFW_KEY_J,
                "key.categories.roadweaver"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            syncScreenPauseState(client);
            if (client.player == null) return;

            // 手绘路线自动保存：编辑后有改动才落盘，避免崩溃/强退丢线。
            // ⚠️ 必须放在 RoadMapScreen 提前返回【之前】——
            //    玩家恰恰是在地图界面上画线，放后面会导致地图里画的永远不落盘。
            net.shiroha233.roadweaver.route.editor.RouteEditorSession.get().tickAutoSave(client);

            if (client.screen instanceof RoadMapScreen) return;

            // ── 世界内：切换手绘路线编辑模式 ──
            while (TOGGLE_ROUTE_EDIT.consumeClick()) {
                var session = net.shiroha233.roadweaver.route.editor.RouteEditorSession.get();
                session.setActive(!session.isActive());
                if (session.isActive()) {
                    session.currentOrCreate();
                    net.shiroha233.roadweaver.route.editor.RouteWorldRenderer.onEnterEditMode();
                } else {
                    net.shiroha233.roadweaver.route.editor.RouteWorldRenderer.onExitEditMode();
                }
            }

            // ── 世界内编辑：射线选中 + 方向键挪位 + Tab/Delete/Insert ──
            if (net.shiroha233.roadweaver.route.editor.RouteWorldRenderer.isEditing()) {
                net.shiroha233.roadweaver.route.editor.RouteWorldEditor.updateSelection(client);
                net.shiroha233.roadweaver.route.editor.RouteWorldEditor.tick(client);
            } else {
                // ★ 审计 R3-P1-1：**没开编辑模式时也要 drain**。
                //
                // RouteWorldEditor.tick 只在编辑模式下被调用，所以「没进编辑模式
                // 就按下的动作键」谁都不消费 —— 它们会在 KeyMapping.clickCount 里
                // 一直攒着（只有 setScreen 才清空），等到玩家第一次进编辑模式
                // 并按住 Ctrl 时集中爆发。CONFIRM_BUILD 默认 F 与原版换副手同键，
                // 所以「刚进游戏按了几次 F 换副手」就足以埋雷。
                net.shiroha233.roadweaver.route.editor.RouteWorldEditor.drainActionKeys();
            }

            while (OPEN_MAP.consumeClick()) {
                if (!ClientMapAccessGuard.canOpen(client)) {
                    continue;
                }
                client.setScreen(new RoadMapScreen());
            }
        });
    }

    /**
     * 用存档里的路线重建服务端登记表（★ 审计 R3-P1-4）。
     *
     * <p>在**服务端线程**上跑：{@code RouteBuildService.rebuildRegistryFromStorage}
     * 会调 {@code RouteInjector.plan}（含地形采样）并写
     * {@code PlannedPathCache} —— 那些都是服务端数据，不能在客户端线程碰。</p>
     *
     * <p>拿不到单人服务端（多人游戏）时直接跳过：多人下路线本来就不落盘，
     * 没有任何东西可重建。</p>
     */
    /**
     * 取「离开世界清理」用的 {@code ServerLevel}（★ Codex 审查 D-1 配套）。
     *
     * <p>{@code client.level} 是 {@code ClientLevel}，不能传给只接受
     * {@link net.minecraft.server.level.ServerLevel} 的注册表 API。
     * 这里走与 {@link #rebuildRouteRegistry} 相同的路径：单人从集成服务器取主世界。</p>
     *
     * <p>拿不到一律返回 {@code null}（多人 / 已断开 / 服务器已关）——
     * 此时注册表的分区会由 {@code WeakHashMap} 在 level 被 GC 后自然回收，
     * 不会永久泄漏。清理方法内部对 null 静默返回。</p>
     */
    private static net.minecraft.server.level.ServerLevel resolveOverworldForCleanup(
            Minecraft client) {
        try {
            var server = client.getSingleplayerServer();
            return server == null ? null : server.overworld();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static void rebuildRouteRegistry(Minecraft client) {
        try {
            var server = client.getSingleplayerServer();
            if (server == null) {
                return;
            }
            var level = server.overworld();
            if (level == null) {
                return;
            }
            // 取会话里**全部**路线（含 READY 的）—— READY 的才是重进世界后
            // 最需要恢复 kind/高度的那些。
            var routes = net.shiroha233.roadweaver.route.editor.RouteEditorSession.get().allRoutes();
            if (routes.isEmpty()) {
                return;
            }
            server.execute(() -> {
                try {
                    net.shiroha233.roadweaver.route.RouteBuildService
                            .rebuildRegistryFromStorage(level, routes);
                } catch (Throwable t) {
                    // 重建失败不致命：退回算法结果（路仍会建，只是不完全是玩家意图）
                    org.slf4j.LoggerFactory.getLogger("roadweaver/route-build")
                            .warn("重进世界重建手绘路线登记失败", t);
                }
            });
        } catch (RuntimeException | LinkageError e) {
            // 环境不支持时静默跳过
        }
    }

    /**
     * 0.8.348 引入、0.8.351 修正：把「是否有界面打开」同步给 {@code RuntimeLoadPolicy}，
     * 供 GPU 粗采样与 CPU worker 让路。
     *
     * <p>背景（真机取证 20260912 夜）：玩家开背包 / ESC / 熔炉 / 设置时卡顿。根因是开 GUI 属于
     * **纯客户端行为** —— 不发服务端交互包（{@code PlayerChunkPriorityGuard.markInteraction()}
     * 的唯一入口是 5 个服务端包处理器：挖掘/放置/使用/攻击/交互方块），且玩家位置不变，
     * 于是 {@code INTERACTION_ACTIVE} 与 {@code RuntimePlayerActivity} 两个信号都不亮；
     * 而 0.8.344 的地图闸只由 {@code RoadMapScreen} 设置，覆盖不到这些界面。</p>
     *
     * <p><b>0.8.351 修正：不再排除聊天栏。</b>0.8.348 曾刻意排除 {@code ChatScreen}，
     * 理由是「打字时后台继续跑是期望行为」。真机证伪（20260913 02:20 会话）：
     * 玩家开聊天后 6 秒 RoadWeaver 即恢复提交（日志 {@code 02:20:06 Loading text cursor}
     * → {@code 02:20:12 active=1}），玩家当场报告「打开聊天界面卡」。
     * 结论：聊天同样是玩家正在操作的界面，暂停几秒对生成毫无影响，但卡顿玩家立刻能感知 ——
     * 因此聊天一并让路，与其他界面同待遇（由同一个 {@code pauseCoarseGpuWhileGuiOpen} 控制）。</p>
     *
     * <p>{@code RoadMapScreen} 由它自己通过 {@code setMapScreenOpen} 上报，此处跳过以免重复。</p>
     */
    private static void syncScreenPauseState(Minecraft client) {
        Screen screen = client.screen;
        boolean guiOpen = screen != null && !(screen instanceof RoadMapScreen);
        RuntimeLoadPolicy.setGuiScreenOpen(guiOpen);
    }
}