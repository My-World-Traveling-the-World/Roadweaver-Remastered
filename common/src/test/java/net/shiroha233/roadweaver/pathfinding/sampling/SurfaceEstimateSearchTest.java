package net.shiroha233.roadweaver.pathfinding.sampling;

import java.lang.reflect.Proxy;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 直接调用生产二分内核，防止负高度静默退回地底；不声称替代真实NoiseChunk验收。 */
class SurfaceEstimateSearchTest {
    @Test void everySignedThresholdStaysInsideWorldAndMatchesDensity() {
        for (int threshold = -65; threshold <= 320; threshold++) {
            int cut = threshold;
            int[] calls = {0};
            DensityFunction density = density((x,y,z) -> {
                assertTrue(++calls[0] <= 10, "二分必须收敛，不能绕回异常高度");
                assertTrue(y >= -64 && y < 320, "只可查询世界搜索区间");
                assertEquals(-4, x, "负坐标必须沿用floorDiv量化");
                assertEquals(4, z);
                return y <= cut ? 1 : 0;
            });
            assertEquals(Math.min(cut,319), NoiseChunkHeightSampler.preliminarySurface(density,-64,384,-1,7));
            assertTrue(calls[0] > 0, "空转不能冒充完成密度查询");
        }
    }

    @Test void strictDensityThresholdAndPositiveDimensionKeepOriginalMeaning() {
        assertEquals(-65, NoiseChunkHeightSampler.preliminarySurface(density((x,y,z)->0.390625),-64,384,0,0));
        for (int cut : new int[]{15,16,20,80,143,144}) {
            assertEquals(Math.min(cut,143), NoiseChunkHeightSampler.preliminarySurface(
                    density((x,y,z)->y<=cut?1:0),16,128,0,0));
        }
    }

    private interface Sample { double compute(int x,int y,int z); }
    private static DensityFunction density(Sample sample) {
        // 仅替代密度接口，算术、坐标量化和结束条件都执行生产代码。
        return (DensityFunction) Proxy.newProxyInstance(DensityFunction.class.getClassLoader(),
                new Class<?>[]{DensityFunction.class},(proxy,method,args)-> {
                    if (method.getName().equals("compute")) {
                        var p=(DensityFunction.FunctionContext)args[0];
                        return sample.compute(p.blockX(),p.blockY(),p.blockZ());
                    }
                    throw new AssertionError("unexpected density operation: "+method.getName());
                });
    }
}
