/* 文件职责：只描述世界高度读取，离线夹具不需要构造 ServerLevel。 */
package net.shiroha233.roadweaver.pathfinding.sampling;

interface CpuAccurateHeightSource extends AutoCloseable {
    boolean supportsColumns();
    AccurateHeightChunk sampleChunk(int chunkX, int chunkZ);
    AccurateHeightChunk sampleColumns(int chunkX, int chunkZ, int[] columns);
    AccurateHeightSample sampleBaseHeights(int x, int z);
    @Override void close();
}
