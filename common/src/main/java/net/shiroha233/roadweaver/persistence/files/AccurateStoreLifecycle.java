/* 文件职责：排空精确样本文件访问，保证关闭返回后不再触碰文件或缓存。 */
package net.shiroha233.roadweaver.persistence.files;

import java.util.concurrent.locks.ReentrantReadWriteLock;

/** 读写磁盘共享租约，关闭独占；不把不同瓦片的正常访问串行化。 */
final class AccurateStoreLifecycle {
    // 公平锁让关闭不会被持续进入的新请求饿死；瓦片内的读改写仍由原瓦片锁保护。
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    private final Access access = lock.readLock()::unlock;
    private boolean closed;

    Access enter() {
        lock.readLock().lock();
        if (closed) {
            lock.readLock().unlock();
            throw new IllegalStateException("Accurate sample store is closed");
        }
        return access;
    }

    void close(Runnable release) {
        // 读锁升级不会成功；遇到输入回调重入时明确拒绝，避免永久卡住卸载线程。
        if (lock.getReadHoldCount() != 0) {
            throw new IllegalStateException("Cannot close accurate sample store during its own access");
        }
        lock.writeLock().lock();
        try {
            if (closed) return;
            closed = true;
            release.run();
        } finally {
            lock.writeLock().unlock();
        }
    }

    interface Access extends AutoCloseable {
        @Override
        void close();
    }
}
