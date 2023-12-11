package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

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

    /**
     * 根据id查询商铺（添加Redis缓存）
     */
    @Override
    public Result queryById(Long id) {
        String shopKey = CACHE_SHOP_KEY + id;
        //1.从Redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(shopKey);
        //2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //3.存在->将Json转为Shop对象->返回商铺信息
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
        //4.不存在->根据id查询数据库
        Shop shop = getById(id);
        //5.判断数据库中商品是否存在
        if (shop == null) {
            //6.不存在->返回错误状态码404
            return Result.fail("店铺不存在！");
        }
        //7.存在->将商铺数据转为JSON写入Redis->返回商铺信息
        stringRedisTemplate.opsForValue().set(shopKey, JSONUtil.toJsonStr(shop));
        return Result.ok(shop);
    }
}
