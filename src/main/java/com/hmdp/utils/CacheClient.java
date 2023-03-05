package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.apache.tomcat.jni.Local;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Component
@Slf4j
public class CacheClient {
    private StringRedisTemplate stringRedisTemplate;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);


    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 可以设置过期时间
     *
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void set(String key, Object value, long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    /**
     * 逻辑过期-处理缓存冲击的问题
     *
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void setLogicalExpire(String key, Object value, long time, TimeUnit unit) {
        //设置逻辑删除
        RedisData redisData = new RedisData();
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(time));
        redisData.setData(value);
        //写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 缓存穿透：缓存空值
     *
     * @param keyPrefix
     * @param id
     * @param <R>
     * @param <ID>
     * @return
     */
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> doFallBack, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        //1.查询redis缓存中是否存在
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.存在，返回
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        if (json != null) {
            return null;
        }
        //3.不存在，查询数据库
        R r = doFallBack.apply(id);
        //4.数据库不存在
        if (r == null) {
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //5.存在更新redis缓存
        this.set(key, r, time, unit);
        //6.返回信息
        return r;
    }

    public <ID, R> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID, R> doFallBack, Long time, TimeUnit unit) {
        //1.查询redis缓存中是否存在
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.不存在，返回null
        if (StrUtil.isBlank(json)) {
            return null;
        }
        //3.存在 判断是否过期
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //4.未过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            return r;
        }
        //5.尝试获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        if (tryLock(lockKey)) {
            //6.获取成功，开启独立线程
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //查询数据库
                    R apply = doFallBack.apply(id);
                    //更新redis缓存
                    this.setLogicalExpire(key, apply, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放互斥锁
                    unLock(lockKey);
                }

            });
        }
        //7.返回过期的信息
        return r;
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }
}
