package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {
        String key = "cache:shop_type";
        // 1. 查找Redis
        String shopTypeJson = stringRedisTemplate.opsForValue().get(key);
        // 2. 找到，return
        if (StrUtil.isNotBlank(shopTypeJson)) {
            List<ShopType> typeList = JSONUtil.toList(shopTypeJson, ShopType.class);
            return Result.ok(typeList);
        }
        // 3. 没找到，database里找
        List<ShopType> typeList = query().orderByAsc("sort").list();
        // 4. database里没有
        if (typeList == null || typeList.isEmpty()) {
            return Result.fail("Categories do not exist");
        }
        // 5. 写入Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(typeList), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 6. Return
        return Result.ok(typeList);
    }
}
