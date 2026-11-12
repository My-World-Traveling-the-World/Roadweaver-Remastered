/* 文件职责：用 Xaero 小地图当底图的地图背景渲染（可选集成）。 */
package net.shiroha233.roadweaver.client.map;

import dev.architectury.platform.Platform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Uses Xaero's existing map session as the terrain background. */
final class XaeroMapBackgroundRenderer {
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    private static final String XAERO_MOD_ID = "xaeroworldmap";
    private static final String SESSION_CLASS = "xaero.map.WorldMapSession";
    private static final String MAP_SCREEN_CLASS = "xaero.map.gui.GuiMap";

    private Binding binding;
    private Object xaeroScreen;
    private Object mapProcessor;
    private int initializedWidth = -1;
    private int initializedHeight = -1;
    private String initializedDimension;
    private boolean permanentlyUnavailable;
    private boolean failureLogged;

    boolean render(GuiGraphics graphics,
                   Minecraft minecraft,
                   Screen parent,
                   MapView view,
                   int width,
                   int height,
                   int mouseX,
                   int mouseY,
                   float partialTick) {
        if (graphics == null || minecraft == null || minecraft.player == null || view == null) {
            return false;
        }
        if (permanentlyUnavailable || !Platform.isModLoaded(XAERO_MOD_ID)) {
            return false;
        }

        try {
            Binding activeBinding = binding != null ? binding : (binding = Binding.resolve());
            Object session = activeBinding.currentSession.invoke(null);
            if (session == null || !Boolean.TRUE.equals(activeBinding.sessionUsable.invoke(session))) {
                return false;
            }
            Object processor = activeBinding.getMapProcessor.invoke(session);
            if (processor == null) return false;

            String dimension = minecraft.level != null
                    ? minecraft.level.dimension().location().toString()
                    : "";
            if (xaeroScreen == null
                    || processor != mapProcessor
                    || width != initializedWidth
                    || height != initializedHeight
                    || !dimension.equals(initializedDimension)) {
                initializeScreen(activeBinding, processor, minecraft, parent, width, height, dimension);
            }

            double centerX = (view.getMinX() + view.getMaxX()) * 0.5;
            double centerZ = (view.getMinZ() + view.getMaxZ()) * 0.5;
            double roadPixelsPerBlock = Math.max(1.0E-6,
                    Math.min(view.pxPerBlockX(width), view.pxPerBlockZ(height)));
            double guiScale = minecraft.getWindow().getGuiScale();
            double xaeroScaleMultiplier = Math.max(1.0,
                    Math.min(minecraft.getWindow().getWidth(), minecraft.getWindow().getHeight()) / 1080.0);
            double xaeroUserScale = roadPixelsPerBlock * guiScale / xaeroScaleMultiplier;
            boolean previousHiddenUi = activeBinding.hiddenUi.getBoolean(null);
            boolean previousAttachedCamera = activeBinding.attachedCamera.getBoolean(null);
            double previousDestinationScale = activeBinding.destinationScale.getDouble(null);
            try {
                activeBinding.cameraX.setDouble(xaeroScreen, centerX);
                activeBinding.cameraZ.setDouble(xaeroScreen, centerZ);
                activeBinding.scale.setDouble(xaeroScreen, roadPixelsPerBlock * guiScale);
                activeBinding.userScale.setDouble(xaeroScreen, xaeroUserScale);
                activeBinding.destinationScale.setDouble(null, xaeroUserScale);
                activeBinding.shouldResetCameraPos.setBoolean(xaeroScreen, false);
                clearXaeroInputAndAnimation(activeBinding);
                activeBinding.hiddenUi.setBoolean(null, true);
                activeBinding.render.invoke(xaeroScreen, graphics, mouseX, mouseY, partialTick);
            } finally {
                activeBinding.hiddenUi.setBoolean(null, previousHiddenUi);
                activeBinding.attachedCamera.setBoolean(null, previousAttachedCamera);
                activeBinding.destinationScale.setDouble(null, previousDestinationScale);
            }
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            disableAfterFailure(failure);
            return false;
        }
    }

    private void clearXaeroInputAndAnimation(Binding activeBinding) throws IllegalAccessException {
        activeBinding.attachedCamera.setBoolean(null, false);
        activeBinding.mouseDownPosX.setInt(xaeroScreen, -1);
        activeBinding.mouseDownPosY.setInt(xaeroScreen, -1);
        activeBinding.mouseCheckPosX.setInt(xaeroScreen, -1);
        activeBinding.mouseCheckPosY.setInt(xaeroScreen, -1);
        activeBinding.previousMouseCheckTime.setLong(xaeroScreen, -1L);
        activeBinding.buttonPressed.set(xaeroScreen, null);
        activeBinding.cameraDestination.set(xaeroScreen, null);
        activeBinding.cameraDestinationAnimX.set(xaeroScreen, null);
        activeBinding.cameraDestinationAnimZ.set(xaeroScreen, null);
        activeBinding.zoomAnimation.set(xaeroScreen, null);
        clearMouseButton(activeBinding, activeBinding.leftMouseButton.get(xaeroScreen));
        clearMouseButton(activeBinding, activeBinding.rightMouseButton.get(xaeroScreen));
    }

    private static void clearMouseButton(Binding activeBinding, Object button) throws IllegalAccessException {
        if (button == null) return;
        activeBinding.mouseButtonDown.setBoolean(button, false);
        activeBinding.mouseButtonClicked.setBoolean(button, false);
    }

    void reset() {
        xaeroScreen = null;
        mapProcessor = null;
        initializedWidth = -1;
        initializedHeight = -1;
        initializedDimension = null;
    }

    private void initializeScreen(Binding activeBinding,
                                  Object processor,
                                  Minecraft minecraft,
                                  Screen parent,
                                  int width,
                                  int height,
                                  String dimension) throws ReflectiveOperationException {
        Object nextScreen = activeBinding.constructor.newInstance(
                parent, parent, processor, minecraft.player);
        activeBinding.initialize.invoke(nextScreen, minecraft, width, height);
        xaeroScreen = nextScreen;
        mapProcessor = processor;
        initializedWidth = width;
        initializedHeight = height;
        initializedDimension = dimension;
    }

    private void disableAfterFailure(Throwable failure) {
        permanentlyUnavailable = true;
        reset();
        if (!failureLogged) {
            failureLogged = true;
            LOGGER.warn("Xaero world-map background integration was disabled; RoadWeaver overlays remain available.",
                    failure);
        }
    }

    private record Binding(Method currentSession,
                           Method sessionUsable,
                           Method getMapProcessor,
                           Constructor<?> constructor,
                           Method initialize,
                           Method render,
                           Field cameraX,
                           Field cameraZ,
                           Field scale,
                           Field userScale,
                           Field destinationScale,
                           Field shouldResetCameraPos,
                           Field cameraDestination,
                           Field cameraDestinationAnimX,
                           Field cameraDestinationAnimZ,
                           Field zoomAnimation,
                           Field attachedCamera,
                           Field mouseDownPosX,
                           Field mouseDownPosY,
                           Field mouseCheckPosX,
                           Field mouseCheckPosY,
                           Field previousMouseCheckTime,
                           Field buttonPressed,
                           Field leftMouseButton,
                           Field rightMouseButton,
                           Field mouseButtonDown,
                           Field mouseButtonClicked,
                           Field hiddenUi) {
        static Binding resolve() throws ReflectiveOperationException {
            ClassLoader loader = XaeroMapBackgroundRenderer.class.getClassLoader();
            Class<?> sessionClass = Class.forName(SESSION_CLASS, false, loader);
            Class<?> mapScreenClass = Class.forName(MAP_SCREEN_CLASS, false, loader);
            return new Binding(
                    sessionClass.getMethod("getCurrentSession"),
                    sessionClass.getMethod("isUsable"),
                    sessionClass.getMethod("getMapProcessor"),
                    findMapConstructor(mapScreenClass),
                    findInitializeMethod(mapScreenClass),
                    findRenderMethod(mapScreenClass),
                    field(mapScreenClass, "cameraX"),
                    field(mapScreenClass, "cameraZ"),
                    field(mapScreenClass, "scale"),
                    field(mapScreenClass, "userScale"),
                    field(mapScreenClass, "destScale"),
                    field(mapScreenClass, "shouldResetCameraPos"),
                    field(mapScreenClass, "cameraDestination"),
                    field(mapScreenClass, "cameraDestinationAnimX"),
                    field(mapScreenClass, "cameraDestinationAnimZ"),
                    field(mapScreenClass, "zoomAnim"),
                    field(mapScreenClass, "attachedCamera"),
                    field(mapScreenClass, "mouseDownPosX"),
                    field(mapScreenClass, "mouseDownPosY"),
                    field(mapScreenClass, "mouseCheckPosX"),
                    field(mapScreenClass, "mouseCheckPosY"),
                    field(mapScreenClass, "prevMouseCheckTimeNano"),
                    field(mapScreenClass, "buttonPressed"),
                    field(mapScreenClass, "leftMouseButton"),
                    field(mapScreenClass, "rightMouseButton"),
                    field(Class.forName("xaero.map.gui.MapMouseButtonPress", false, loader), "isDown"),
                    field(Class.forName("xaero.map.gui.MapMouseButtonPress", false, loader), "clicked"),
                    field(mapScreenClass, "hiddenUI"));
        }

        private static Constructor<?> findMapConstructor(Class<?> type) throws NoSuchMethodException {
            for (Constructor<?> constructor : type.getConstructors()) {
                Class<?>[] parameters = constructor.getParameterTypes();
                if (parameters.length == 4
                        && Screen.class.isAssignableFrom(parameters[0])
                        && Screen.class.isAssignableFrom(parameters[1])) {
                    constructor.setAccessible(true);
                    return constructor;
                }
            }
            throw new NoSuchMethodException("Xaero GuiMap constructor");
        }

        private static Method findRenderMethod(Class<?> type) throws NoSuchMethodException {
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                for (Method method : current.getDeclaredMethods()) {
                    Class<?>[] parameters = method.getParameterTypes();
                    if (parameters.length == 4
                            && parameters[0] == GuiGraphics.class
                            && parameters[1] == int.class
                            && parameters[2] == int.class
                            && parameters[3] == float.class
                            && method.getReturnType() == void.class
                            && (method.getName().equals("render")
                            || method.getName().equals("method_25394"))) {
                        method.setAccessible(true);
                        return method;
                    }
                }
            }
            throw new NoSuchMethodException("Xaero GuiMap render method");
        }

        private static Method findInitializeMethod(Class<?> type) throws NoSuchMethodException {
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                for (Method method : current.getDeclaredMethods()) {
                    Class<?>[] parameters = method.getParameterTypes();
                    if (parameters.length == 3
                            && parameters[0] == Minecraft.class
                            && parameters[1] == int.class
                            && parameters[2] == int.class
                            && (method.getName().equals("init")
                            || method.getName().equals("method_25423"))) {
                        method.setAccessible(true);
                        return method;
                    }
                }
            }
            throw new NoSuchMethodException("method with requested parameters on " + type.getName());
        }

        private static Field field(Class<?> type, String name) throws NoSuchFieldException {
            Field value = type.getDeclaredField(name);
            value.setAccessible(true);
            return value;
        }
    }
}
