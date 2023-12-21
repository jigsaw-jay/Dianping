package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private CacheClient cacheClient;

    /**
     * 根据id查询商铺（添加Redis缓存,并实现缓存击穿->逻辑过期）
     */
    @Override
    public Result queryById(Long id) {
        //逻辑过期
        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //互斥锁
        //Shop shop = cacheClient.queryWithMutex(CACHE_SHOP_KEY, id, Shop.class, this::getById, 20L, TimeUnit.SECONDS);
        //缓存穿透
        //Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        if (shop == null) {
            return Result.fail("店铺不存在！");
        }
        return Result.ok(shop);
    }

    /**
     * 更新商铺信息，加入缓存更新策略
     */
    @Override
    @Transactional
    public Result update(Shop shop) {
        if (shop.getId() == null) {
            return Result.fail("店铺ID不能为空！！");
        }
        String shopKey = CACHE_SHOP_KEY + shop.getId();
        //1.更新数据库
        updateById(shop);
        //2.删除缓存
        stringRedisTemplate.delete(shopKey);
        return Result.ok();
    }

    /**
     * 根据商铺类型分页查询商铺信息
     *
     * @param typeId
     * @param current
     * @param x
     * @param y
     * @return
     */
    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //1.判断是否需要根据经纬度查询
        if (x == null || y == null) {
            //1.1不需要坐标查询->数据库直接查询
            Page<Shop> shopPage = query()
                    .eq("type_id", typeId)
                    .orderByAsc("score")
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(shopPage.getRecords());
        }
        //2.计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        //3.查询redis，按照距离排序、分页->得到shopId、distance
        String key = SHOP_GEO_KEY + typeId;
        //key+圆心+半径+Distance+分页查询限制数
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end));

        //4.解析出shopId，根据shopId查询Shop
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            //没有下一页，结束
            return Result.ok(Collections.emptyList());
        }
        //4.1截取from ~ end 部分(通过stream流跳过)
        List<Long> shopIds = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        //利用skip跳过之前已经查询的数据
        list.stream().skip(from).forEach(result -> {
            //4.2获取店铺Id和distance
            String shopIdStr = result.getContent().getName();
            shopIds.add(Long.valueOf(shopIdStr));
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        //4.3根据shopIds查询Shop
        String idstr = StrUtil.join(",", shopIds);
        List<Shop> shopList = query().in("id", shopIds).last("ORDER BY FIELD(id," + idstr + ")").list();
        //4.4 插入distance值
        for (Shop shop : shopList) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        //5.返回分页信息
        return Result.ok(shopList);
    }
}
