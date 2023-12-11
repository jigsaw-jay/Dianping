package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * shopType缓存
     */
    @Override
    public List<ShopType> queryList() {
        //1.从Redis中获取JSON字符串
        String shopTypeJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_TYPE);
        List<ShopType> shopTypes;
        //2.如果非空则将JSON字符串转为List<ShopType>集合返回
        if (StrUtil.isNotBlank(shopTypeJson)) {
            shopTypes = JSONUtil.toList(shopTypeJson, ShopType.class);
            return shopTypes;
        }
        //3.如果为空，则从数据库中查询全部，按sort顺序升序
        LambdaQueryWrapper<ShopType> lqw = new LambdaQueryWrapper<>();
        lqw.orderByAsc(ShopType::getSort);
        shopTypes = this.list(lqw);
        //4.将集合转为JSON字符串，存入Redis
        String jsonStr = JSONUtil.toJsonStr(shopTypes);
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_TYPE, jsonStr);
        return shopTypes;
    }
}
