package com.hmdp.utils;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.concurrent.TimeUnit;

/**
 * Redisson分布式锁工具类，统一封装锁的获取、尝试加锁和释放。
 */
@Component
public class RedissonLockUtil {

    @Resource
    private RedissonClient redissonClient;

    /**
     * 根据业务key获取Redisson锁对象，调用方可使用更完整的RLock能力。
     */
    public RLock getLock(String lockKey) {
        return redissonClient.getLock(lockKey);
    }

    /**
     * 立即尝试加锁，不指定租约时间时由Redisson看门狗自动续期。
     */
    public boolean tryLock(String lockKey) {
        return getLock(lockKey).tryLock();
    }

    /**
     * 立即尝试加锁，并设置锁租约时间，避免业务异常时锁长期不释放。
     */
    public boolean tryLock(String lockKey, long leaseTime, TimeUnit unit) throws InterruptedException {
        return tryLock(lockKey, 0L, leaseTime, unit);
    }

    /**
     * 在指定等待时间内尝试加锁，适合允许短暂排队的业务场景。
     */
    public boolean tryLock(String lockKey, long waitTime, long leaseTime, TimeUnit unit) throws InterruptedException {
        return getLock(lockKey).tryLock(waitTime, leaseTime, unit);
    }

    /**
     * 释放指定key的锁，只允许当前线程释放自己持有的锁，避免误解锁。
     */
    public void unlock(String lockKey) {
        unlock(getLock(lockKey));
    }

    /**
     * 释放传入的锁对象，先校验当前线程是否持有锁。
     */
    public void unlock(RLock lock) {
        if (lock != null && lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
