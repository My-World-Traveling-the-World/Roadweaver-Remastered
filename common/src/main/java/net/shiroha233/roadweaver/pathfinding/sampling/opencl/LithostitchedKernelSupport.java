/* 文件职责：向 RoadWeaver 自有 OpenCL kernel 注入 Lithostitched FastNoise 随机向量表。 */
package net.shiroha233.roadweaver.pathfinding.sampling.opencl;

// 日志保留原诊断标记与字段以兼容分析工具；中文主说明使用编译期常量，避免热路径翻译开销。

import java.lang.reflect.Field;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class LithostitchedKernelSupport {
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    private static final String TABLE_PLACEHOLDER = "/*__LITHOSTITCHED_FAST_NOISE_TABLE__*/";
    private static final String FNL_CLASS =
            "dev.worldgen.lithostitched.api.worldgen.densityfunction.fastnoise.FNL";
    private static volatile float[] randomVectors;
    private static volatile boolean resolved;

    private LithostitchedKernelSupport() {
    }

    static String inject(String source) {
        float[] vectors = randomVectors();
        StringBuilder declaration = new StringBuilder(18_000);
        declaration.append("static __constant float lithostitched_rand_vecs3d[1024] = {");
        if (vectors == null || vectors.length != 1024) {
            for (int i = 0; i < 1024; i++) declaration.append("0.0f,");
        } else {
            for (float vector : vectors) {
                declaration.append(Float.toHexString(vector)).append('f').append(',');
            }
        }
        declaration.append("};");
        if (!source.contains(TABLE_PLACEHOLDER)) {
            throw new IllegalStateException("RoadWeaver kernel Lithostitched placeholder is missing");
        }
        return source.replace(TABLE_PLACEHOLDER, declaration.toString());
    }

    static boolean hasRandomVectors() {
        float[] vectors = randomVectors();
        return vectors != null && vectors.length == 1024;
    }

    private static float[] randomVectors() {
        if (resolved) return randomVectors;
        synchronized (LithostitchedKernelSupport.class) {
            if (resolved) return randomVectors;
            try {
                Class<?> fnl = Class.forName(FNL_CLASS, false,
                        LithostitchedKernelSupport.class.getClassLoader());
                Field field = fnl.getDeclaredField("RandVecs3D");
                field.setAccessible(true);
                Object value = field.get(null);
                if (value instanceof float[] vectors && vectors.length == 1024) {
                    randomVectors = vectors.clone();
                    LOGGER.info("【Lithostitched噪声向量已加载】 " + "RoadWeaver loaded Lithostitched FastNoise vectors: count={}", vectors.length);
                } else {
                    LOGGER.warn("【Lithostitched噪声向量结构不符合预期】 " + "RoadWeaver Lithostitched FastNoise vectors have unexpected shape");
                }
            } catch (Throwable failure) {
                LOGGER.warn("【Lithostitched噪声向量不可用】 " + "RoadWeaver Lithostitched FastNoise vectors unavailable: {}", failure.toString());
            } finally {
                resolved = true;
            }
            return randomVectors;
        }
    }
}
