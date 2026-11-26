package net.shiroha233.roadweaver.client.fabric.diorama;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.launch.platform.container.ContainerHandleVirtual;
import org.spongepowered.asm.launch.platform.container.IContainerHandle;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.service.*;

/** 隔离 JVM 中真实应用 Sponge，避免实例化持有游戏或显卡资源的 Voxy 对象。 */
public final class DioramaMixinHarness extends MixinServiceAbstract implements IClassProvider, IClassBytecodeProvider {
    private final Map<String, byte[]> classes = new HashMap<>();
    private final String configuration;
    private final Map<String, String> extraConfigurations = new HashMap<>();
    private IMixinTransformer transformer;

    public DioramaMixinHarness(String jar, String configuration) throws IOException {
        this.configuration = configuration;
        addJar(jar);
    }

    public void addJar(String jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar)) {
            for (var entries = zip.entries(); entries.hasMoreElements();) {
                var entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) continue;
                try (var input = zip.getInputStream(entry)) {
                    classes.put(entry.getName(), input.readAllBytes());
                }
            }
        }
    }
    public void addConfiguration(String name, String text) { extraConfigurations.put(name, text); }

    public byte[] apply(String name) throws Exception {
        if (transformer == null) {
            var instance = MixinService.class.getDeclaredMethod("getInstance");
            instance.setAccessible(true);
            Object singleton = instance.invoke(null);
            var service = MixinService.class.getDeclaredField("service");
            service.setAccessible(true);
            service.set(singleton, this);
            var properties = MixinService.class.getDeclaredField("propertyService");
            properties.setAccessible(true);
            properties.set(singleton, new Properties());
            MixinBootstrap.init();
            MixinEnvironment.getCurrentEnvironment().setSide(MixinEnvironment.Side.CLIENT);
            Mixins.addConfiguration("roadweaver.diorama.test.mixins.json");
            extraConfigurations.keySet().forEach(Mixins::addConfiguration);
            var constructor = Class.forName("org.spongepowered.asm.mixin.transformer.MixinTransformer")
                    .getDeclaredConstructor();
            constructor.setAccessible(true);
            transformer = (IMixinTransformer) constructor.newInstance();
            // 先建立真实变换器，MixinExtras 注册扩展时会查询它。
            MixinEnvironment.getCurrentEnvironment().setActiveTransformer(transformer);
            com.llamalad7.mixinextras.MixinExtrasBootstrap.init();
        }
        byte[] bytes = classes.get(name.replace('.', '/') + ".class");
        if (bytes == null) try (InputStream input = getResourceAsStream(name.replace('.', '/') + ".class")) {
            if (input != null) bytes = input.readAllBytes();
        }
        if (bytes == null) throw new ClassNotFoundException(name);
        return transformer.transformClass(MixinEnvironment.getCurrentEnvironment(), name, bytes);
    }

    /** 先安装夹具变体，保证 Sponge 不会缓存变换前的目标元数据。 */
    public void replaceClass(String name, byte[] bytes) {
        if (transformer != null) throw new IllegalStateException("Mixin already prepared");
        classes.put(name.replace('.', '/') + ".class", bytes);
    }

    @Override public String getName() { return "RoadWeaver Diorama regression"; }
    @Override public boolean isValid() { return true; }
    @Override public MixinEnvironment.Phase getInitialPhase() { return MixinEnvironment.Phase.DEFAULT; }
    @Override public IClassProvider getClassProvider() { return this; }
    @Override public IClassBytecodeProvider getBytecodeProvider() { return this; }
    @Override public ITransformerProvider getTransformerProvider() { return null; }
    @Override public IClassTracker getClassTracker() { return null; }
    @Override public IMixinAuditTrail getAuditTrail() { return null; }
    @Override public Collection<String> getPlatformAgents() { return List.of(); }
    @Override public IContainerHandle getPrimaryContainer() { return new ContainerHandleVirtual("RoadWeaver Diorama tests"); }
    @Override public InputStream getResourceAsStream(String name) {
        if (extraConfigurations.containsKey(name))
            return new ByteArrayInputStream(extraConfigurations.get(name).getBytes(StandardCharsets.UTF_8));
        if (name.equals("roadweaver.diorama.test.mixins.json"))
            return new ByteArrayInputStream(configuration.getBytes(StandardCharsets.UTF_8));
        byte[] bytes = classes.get(name);
        return bytes != null ? new ByteArrayInputStream(bytes) : getClass().getClassLoader().getResourceAsStream(name);
    }

    @Override public URL[] getClassPath() { return new URL[0]; }
    @Override public Class<?> findClass(String name) throws ClassNotFoundException { return findClass(name, false); }
    @Override public Class<?> findClass(String name, boolean initialize) throws ClassNotFoundException {
        return Class.forName(name, initialize, getClass().getClassLoader());
    }
    @Override public Class<?> findAgentClass(String name, boolean initialize) throws ClassNotFoundException {
        return findClass(name, initialize);
    }
    @Override public ClassNode getClassNode(String name) throws IOException, ClassNotFoundException {
        return getClassNode(name, true);
    }
    @Override public ClassNode getClassNode(String name, boolean transform) throws IOException, ClassNotFoundException {
        return getClassNode(name, transform, 0);
    }
    // 保留带 flags 的重载，以覆盖不同 Mixin 版本的读取入口。
    public ClassNode getClassNode(String name, boolean transform, int flags)
            throws IOException, ClassNotFoundException {
        try (InputStream input = getResourceAsStream(name.replace('.', '/') + ".class")) {
            if (input == null) throw new ClassNotFoundException(name);
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, flags);
            return node;
        }
    }

    private static final class Properties implements IGlobalPropertyService {
        private record Key(String name) implements IPropertyKey { }
        private final Map<IPropertyKey, Object> values = new HashMap<>();
        @Override public IPropertyKey resolveKey(String name) { return new Key(name); }
        @Override @SuppressWarnings("unchecked") public <T> T getProperty(IPropertyKey key) { return (T) values.get(key); }
        @Override public void setProperty(IPropertyKey key, Object value) { values.put(key, value); }
        @Override @SuppressWarnings("unchecked") public <T> T getProperty(IPropertyKey key, T fallback) {
            return (T) values.getOrDefault(key, fallback);
        }
        @Override public String getPropertyString(IPropertyKey key, String fallback) {
            Object value = values.get(key);
            return value == null ? fallback : value.toString();
        }
    }
}
