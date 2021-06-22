package com.example.zookeeperDemo;

import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.locks.InterProcessLock;
import org.apache.curator.framework.recipes.locks.InterProcessMultiLock;
import org.apache.curator.framework.recipes.locks.InterProcessMutex;
import org.apache.curator.framework.recipes.locks.InterProcessReadWriteLock;
import org.apache.curator.framework.recipes.locks.InterProcessSemaphoreMutex;
import org.apache.curator.framework.recipes.locks.InterProcessSemaphoreV2;
import org.apache.curator.framework.recipes.locks.Lease;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.utils.CloseableUtils;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@RunWith(SpringRunner.class)
@SpringBootTest
public class DistributedLockDemo {

    //参考博客
    //https://blog.csdn.net/xiaojin21cen/article/details/88613900
    // curator recipes 中的各种锁
    // InterProcessMutex：可重入、独占锁
    // InterProcessSemaphoreMutex：不可重入、独占锁
// –
//     InterProcessReadWriteLock：读写锁
//     InterProcessSemaphoreV2 ： 共享信号量
//     InterProcessMultiLock：多重共享锁 （将多个锁作为单个实体管理的容器）

    // ZooKeeper 锁节点路径, 分布式锁的相关操作都是在这个节点上进行
    private final String lockPath = "/distributed-lock";

    // ZooKeeper 服务地址, 单机格式为:(127.0.0.1:2181),
    // 集群格式为:(127.0.0.1:2181,127.0.0.1:2182,127.0.0.1:2183)
    private String connectString = "127.0.0.1:2181";

    // Curator 客户端重试策略
    private RetryPolicy retry;

    // Curator 客户端对象
    private CuratorFramework client1;

    // client2 用户模拟其他客户端
    private CuratorFramework client2;

    // 初始化资源
    @Before
    public void init() throws Exception {
        // 重试策略
        // 初始休眠时间为 1000ms, 最大重试次数为 3
        retry = new ExponentialBackoffRetry(1000, 3);
        // 创建一个客户端, 60000(ms)为 session 超时时间, 15000(ms)为链接超时时间
        client1 = CuratorFrameworkFactory.newClient(connectString, 60000, 15000, retry);
        client2 = CuratorFrameworkFactory.newClient(connectString, 60000, 15000, retry);
        // 创建会话
        client1.start();
        client2.start();
    }

    // 释放资源
    @After
    public void close() {
        CloseableUtils.closeQuietly(client1);
    }

    /**
     * InterProcessMutex：可重入、独占锁
     */
    @Test
    public void sharedReentrantLock() throws Exception {
        // 创建可重入锁
        InterProcessMutex lock1 = new InterProcessMutex(client1, lockPath);
        // lock2 用于模拟其他客户端
        InterProcessMutex lock2 = new InterProcessMutex(client2, lockPath);

        // lock1 获取锁
        lock1.acquire();
        try {
            // lock1 第2次获取锁
            lock1.acquire();
            try {
                // lock2 超时获取锁, 因为锁已经被 lock1 客户端占用, 所以lock2获取锁失败, 需要等 lock1 释放
                Assert.assertFalse(lock2.acquire(2, TimeUnit.SECONDS));
            } finally {
                lock1.release();
            }
        } finally {
            // 重入锁获取与释放需要一一对应, 如果获取 2 次, 释放 1 次, 那么该锁依然是被占用,
            // 如果将下面这行代码注释, 那么会发现下面的 lock2
            // 获取锁失败
            lock1.release();
        }

        // 在 lock1 释放后, lock2 能够获取锁
        Assert.assertTrue(lock2.acquire(2, TimeUnit.SECONDS));
        lock2.release();
    }

    /**
     * InterProcessSemaphoreMutex： 不可重入、独占锁
     */
    @Test
    public void sharedLock() throws Exception {

        InterProcessSemaphoreMutex lock1 = new InterProcessSemaphoreMutex(client1, lockPath);
        // lock2 用于模拟其他客户端
        InterProcessSemaphoreMutex lock2 = new InterProcessSemaphoreMutex(client2, lockPath);

        // 获取锁对象
        lock1.acquire();

        // 测试是否可以重入
        // 因为锁已经被获取, 所以返回 false
        Assert.assertFalse(lock1.acquire(2, TimeUnit.SECONDS));// lock1 返回是false
        Assert.assertFalse(lock2.acquire(2, TimeUnit.SECONDS));// lock2 返回是false

        // lock1 释放锁
        lock1.release();

        // lock2 尝试获取锁成功, 因为锁已经被释放
        Assert.assertTrue(lock2.acquire(2, TimeUnit.SECONDS));// 返回是true
        lock2.release();
        System.out.println("测试结束");
    }

    /**
     * InterProcessReadWriteLock：读写锁.
     * 特点：读写锁、可重入
     */
    @Test
    public void sharedReentrantReadWriteLock() throws Exception {
        // 创建读写锁对象, Curator 以公平锁的方式进行实现
        InterProcessReadWriteLock lock1 = new InterProcessReadWriteLock(client1, lockPath);
        // lock2 用于模拟其他客户端
        InterProcessReadWriteLock lock2 = new InterProcessReadWriteLock(client2, lockPath);

        // 使用 lock1 模拟读操作
        // 使用 lock2 模拟写操作
        // 获取读锁(使用 InterProcessMutex 实现, 所以是可以重入的)
        final InterProcessLock readLock = lock1.readLock();
        // 获取写锁(使用 InterProcessMutex 实现, 所以是可以重入的)
        final InterProcessLock writeLock = lock2.writeLock();

        /**
         * 读写锁测试对象
         */
        class ReadWriteLockTest {
            // 测试数据变更字段
            private Integer testData = 0;
            private Set<Thread> threadSet = new HashSet<>();

            // 写入数据
            private void write() throws Exception {
                writeLock.acquire();
                try {
                    Thread.sleep(10);
                    testData++;
                    System.out.println("写入数据 \t" + testData);
                } finally {
                    writeLock.release();
                }
            }

            // 读取数据
            private void read() throws Exception {
                readLock.acquire();
                try {
                    Thread.sleep(10);
                    System.out.println("读取数据 \t" + testData);
                } finally {
                    readLock.release();
                }
            }

            // 等待线程结束, 防止 test 方法调用完成后, 当前线程直接退出, 导致控制台无法输出信息
            public void waitThread() throws InterruptedException {
                for (Thread thread : threadSet) {
                    thread.join();
                }
            }

            // 创建线程方法
            private void createThread(final int type) {
                Thread thread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (type == 1) {
                                write();
                            } else {
                                read();
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                });
                threadSet.add(thread);
                thread.start();
            }

            // 测试方法
            public void test() {
                for (int i = 0; i < 5; i++) {
                    createThread(1);
                }
                for (int i = 0; i < 5; i++) {
                    createThread(2);
                }
            }
        }

        ReadWriteLockTest readWriteLockTest = new ReadWriteLockTest();
        readWriteLockTest.test();
        readWriteLockTest.waitThread();
    }

    /**
     * InterProcessSemaphoreV2 共享信号量
     */
    @Test
    public void semaphore() throws Exception {
        // 创建一个信号量, Curator 以公平锁的方式进行实现
        InterProcessSemaphoreV2 semaphore1 = new InterProcessSemaphoreV2(client1, lockPath, 6);
        // semaphore2 用于模拟其他客户端
        InterProcessSemaphoreV2 semaphore2 = new InterProcessSemaphoreV2(client2, lockPath, 6);

        // 获取一个许可
        Lease lease1 = semaphore1.acquire();
        Assert.assertNotNull(lease1);
        // semaphore.getParticipantNodes() 会返回当前参与信号量的节点列表, 俩个客户端所获取的信息相同
        Assert.assertEquals(semaphore1.getParticipantNodes(), semaphore2.getParticipantNodes());

        // 超时获取一个许可
        Lease lease2 = semaphore2.acquire(2, TimeUnit.SECONDS);
        Assert.assertNotNull(lease2);
        Assert.assertEquals(semaphore1.getParticipantNodes(), semaphore2.getParticipantNodes());

        // 获取多个许可, 参数为许可数量
        Collection<Lease> leases = semaphore1.acquire(2);
        Assert.assertTrue(leases.size() == 2);
        Assert.assertEquals(semaphore1.getParticipantNodes(), semaphore2.getParticipantNodes());

        // 超时获取多个许可, 第一个参数为许可数量
        Collection<Lease> leases2 = semaphore2.acquire(2, 2, TimeUnit.SECONDS);
        Assert.assertTrue(leases2.size() == 2);
        Assert.assertEquals(semaphore1.getParticipantNodes(), semaphore2.getParticipantNodes());

        // 目前 semaphore 已经获取 3 个许可, semaphore2 也获取 3 个许可, 加起来为 6 个, 所以他们无法再进行许可获取
        Assert.assertNull(semaphore1.acquire(2, TimeUnit.SECONDS));
        Assert.assertNull(semaphore2.acquire(2, TimeUnit.SECONDS));

        // 释放一个许可
        semaphore1.returnLease(lease1);
        semaphore2.returnLease(lease2);
        // 释放多个许可
        semaphore1.returnAll(leases);
        semaphore2.returnAll(leases2);
    }

    /**
     * InterProcessMutex ：可重入、独占锁
     * InterProcessSemaphoreMutex ： 不可重入、独占锁
     * InterProcessMultiLock： 多重共享锁（将多个锁作为单个实体管理的容器）
     */
    @Test
    public void multiLock() throws Exception {

        InterProcessMutex mutex = new InterProcessMutex(client1, lockPath);

        InterProcessSemaphoreMutex semaphoreMutex = new InterProcessSemaphoreMutex(client2, lockPath);

        //将上面的两种锁入到其中
        InterProcessMultiLock multiLock = new InterProcessMultiLock(Arrays.asList(mutex, semaphoreMutex));

        // 获取参数集合中的所有锁
        multiLock.acquire();

        // 因为存在一个不可重入锁, 所以整个 multiLock 不可重入
        Assert.assertFalse(multiLock.acquire(2, TimeUnit.SECONDS));

        // mutex 是可重入锁, 所以可以继续获取锁
        Assert.assertTrue(mutex.acquire(2, TimeUnit.SECONDS));

        // semaphoreMutex  是不可重入锁, 所以获取锁失败
        Assert.assertFalse(semaphoreMutex.acquire(2, TimeUnit.SECONDS));

        // 释放参数集合中的所有锁
        multiLock.release();

        // interProcessLock2 中的锁已经释放, 所以可以获取
        Assert.assertTrue(semaphoreMutex.acquire(2, TimeUnit.SECONDS));
    }
}
