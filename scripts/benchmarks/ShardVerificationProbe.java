/* 文件职责：比较统计扫描、旧全量JSON验证与有界流式验证的真实临时文件耗时。 */
package net.shiroha233.roadweaver.persistence.chunk;
import java.nio.file.*;
import java.util.*;
public final class ShardVerificationProbe {
    private static volatile Object sink;
    public static void main(String[] args) throws Exception {
        for(int n:new int[]{1000,10000,60000}) {
            Path root=Files.createTempDirectory("roadweaver-shards-probe"),directory=Files.createDirectory(root.resolve("chunks"));
            var expected=new LinkedHashMap<Long,long[]>();var paths=new ArrayList<Path>();
            try {
                for(int i=0;i<n;i++){
                    var key=new RoadChunkKey(i,0);Path file=directory.resolve(key.fileName());paths.add(file);expected.put(key.packed(),new long[]{1234567890123456789L});
                    Files.writeString(file,"{\"schema\":\"roadweaver.roads.chunk\",\"version\":2,\"generation\":1,\"chunkX\":"+i+",\"chunkZ\":0,\"roads\":[1234567890123456789]}");
                }
                var old=new FrozenRoadShardVerifier(root);var now=new RoadShardVerifier(root);
                var compute=FrozenRoadShardVerifier.class.getDeclaredMethod("computeShardStamp",Path.class);compute.setAccessible(true);
                // 首轮仅表示新建文件后的首次读取，操作系统缓存未清，不能标为真实冷盘。
                long start=System.nanoTime();boolean legacy=old.matches(expected);long firstOld=System.nanoTime()-start;
                start=System.nanoTime();var result=now.verify(expected);long firstNew=System.nanoTime()-start;
                if(!legacy || !result.matches() || result.checked()!=n)throw new AssertionError("必须真实完整验证");
                for(int w=0;w<2;w++){Files.deleteIfExists(root.resolve("shard-verify.json"));old.matches(expected);now.verify(expected);compute.invoke(old,directory);}
                long[] stats=new long[5],full=new long[5],stream=new long[5];
                for(int r=0;r<5;r++){
                    start=System.nanoTime();sink=compute.invoke(old,directory);stats[r]=System.nanoTime()-start;
                    Files.deleteIfExists(root.resolve("shard-verify.json"));
                    if(r%2==0){start=System.nanoTime();if(!old.matches(expected))throw new AssertionError();full[r]=System.nanoTime()-start;
                        start=System.nanoTime();if(!now.verify(expected).matches())throw new AssertionError();stream[r]=System.nanoTime()-start;}
                    else {start=System.nanoTime();if(!now.verify(expected).matches())throw new AssertionError();stream[r]=System.nanoTime()-start;
                        start=System.nanoTime();if(!old.matches(expected))throw new AssertionError();full[r]=System.nanoTime()-start;}
                }
                Arrays.sort(stats);Arrays.sort(full);Arrays.sort(stream);
                System.out.printf(Locale.ROOT,"SHARD_PROBE files=%d firstOldMs=%.2f firstNewMs=%.2f statsP50Ms=%.2f oldFullP50Ms=%.2f streamP50Ms=%.2f statsMaxMs=%.2f oldFullMaxMs=%.2f streamMaxMs=%.2f oldTasks=%d newTasksAtMost=8%n",
                    n,firstOld/1e6,firstNew/1e6,stats[2]/1e6,full[2]/1e6,stream[2]/1e6,stats[4]/1e6,full[4]/1e6,stream[4]/1e6,n);
            } finally {
                // 仅清理本次显式创建的路径，不递归移动/删除任何用户目录。
                for(Path file:paths)Files.deleteIfExists(file);
                Files.deleteIfExists(root.resolve("shard-verify.json"));Files.deleteIfExists(directory);Files.deleteIfExists(root);
            }
        }
    }
}
