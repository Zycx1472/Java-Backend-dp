package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;


@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public CacheClient(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set (String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        // 逻辑过期时间 = 当前时间 + 用户指定的跨度
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));

        // 写入 Redis，物理 TTL 设为 1天，确保冷数据能被自动清理
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData), 1L, TimeUnit.DAYS);
    }

    /**
     * 方案一：解决缓存穿透（返回空对象/空字符串策略）
     */
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback,
                                          Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1.从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isNotBlank(json)) {
            // 3.存在，直接返回
            return JSONUtil.toBean(json, type);
        }
        //判断命中的是否是空值
        if (json != null) {
            // 返回一个错误信息
            return null;
        }

        // 4.不存在，根据id查询数据库
        R r = dbFallback.apply(id);
        // 5.不存在，返回错误
        if (r == null){
            // 将空值写入reids
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            // 返回错误信息
            return null;
        }

        // 6.存在，写入redis
        this.set(key,r, time, unit);

        return r;
    }

    /**
     * 方案二：逻辑过期解决缓存击穿（带自动同步冷启动）
     */
    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix, String lockKeyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);

        // 1. 自动冷启动逻辑：如果缓存为空，同步查库并补齐逻辑过期格式
        if (StrUtil.isBlank(json)) {
            String lockKey = lockKeyPrefix + id;
            if (tryLock(lockKey)) {
                try {
                    json = stringRedisTemplate.opsForValue().get(key);
                    if (StrUtil.isBlank(json)) {
                        // 1. 同步查库（必须同步，否则没数据返回）
                        R r1 = dbFallback.apply(id);
                        if (r1 == null) return null;

                        // 2. 关键优化：异步写入缓存，不占用当前的响应时间
                        CACHE_REBUILD_EXECUTOR.submit(() -> {
                            this.setWithLogicalExpire(key, r1, time, unit);
                        });

                        // 3. 查完立刻返回，速度比同步写快得多
                        return r1;
                    }
                } finally {
                    unlock(lockKey);
                }
            } else {
                // 没拿到锁的线程，说明有人正在重建，稍微等一下直接查缓存或重试
                try { Thread.sleep(50); } catch (InterruptedException e) {}
                return queryWithLogicalExpire(keyPrefix, lockKeyPrefix, id, type, dbFallback, time, unit);
            }
        }
        /*if (StrUtil.isBlank(json)) {
            String lockKey = lockKeyPrefix + id;
            if (tryLock(lockKey)) {
                try {
                    // Double Check
                    json = stringRedisTemplate.opsForValue().get(key);
                    if (StrUtil.isBlank(json)) {
                        R r1 = dbFallback.apply(id);
                        if (r1 == null) return null;
                        this.setWithLogicalExpire(key, r1, time, unit);
                        return r1;
                    }
                } finally {
                    unlock(lockKey);
                }
            } else {
                // 没拿到锁重试
                try { Thread.sleep(50); } catch (InterruptedException e) { e.printStackTrace(); }
                return queryWithLogicalExpire(keyPrefix, lockKeyPrefix, id, type, dbFallback, time, unit);
            }
        }*/

        // 2. 命中，解析逻辑过期数据
        JSONObject jsonObject = JSONUtil.parseObj(json);

        R r = JSONUtil.toBean(jsonObject.getJSONObject("data"), type);

        LocalDateTime expireTime = jsonObject.get("expireTime", LocalDateTime.class);

        // 3. 判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            return r; // 未过期
        }

        // 4. 已过期，异步重建
        String lockKey = lockKeyPrefix + id;
        if (tryLock(lockKey)) {
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    R newR = dbFallback.apply(id);
                    this.setWithLogicalExpire(key, newR, time, unit);
                } catch (Exception e) {
                    log.error("缓存重建异常", e);
                } finally {
                    unlock(lockKey);
                }
            });
        }
        return r; // 返回旧数据
    }

    /**
     * 方案三：互斥锁解决缓存击穿
     */
    public <R, ID> R queryWithMutex(
            String keyPrefix, String lockKeyPrefix, ID id, Class<R> type,
            Function<ID, R> dbFallback, Long time, TimeUnit unit) {

        String key = keyPrefix + id;
        // 1.从redis查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);

        // 2.判断是否存在 (isNotBlank 排除 null 和 "")
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        // 3.判断命中的是否是空值 (解决缓存穿透存入的 "")
        if (json != null) {
            return null;
        }

        // 4.实现缓存重建
        String lockKey = lockKeyPrefix + id;
        R r = null;
        try {
            // 4.1 获取互斥锁
            boolean isLock = tryLock(lockKey);
            // 4.2 判断是否获取成功
            if (!isLock) {
                // 4.3 失败，则休眠并重试（递归调用）
                Thread.sleep(50);
                return queryWithMutex(keyPrefix, lockKeyPrefix, id, type, dbFallback, time, unit);
            }

            // 4.4 成功，获取锁后应该再次检查缓存 (Double Check)，防止在等待锁期间缓存已生成
            String jsonCheck = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(jsonCheck)) {
                return JSONUtil.toBean(jsonCheck, type);
            }

            // 4.5 根据id查询数据库
            r = dbFallback.apply(id);

            // 5.数据库不存在，返回错误并写入空值（防穿透）
            if (r == null) {
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }

            // 6.存在，写入redis
            this.set(key, r, time, unit);

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 7.释放互斥锁
            unlock(lockKey);
        }
        // 8.返回结果
        return r;
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}
