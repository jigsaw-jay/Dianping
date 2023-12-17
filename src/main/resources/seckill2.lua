--1.参数列表
--1.1 优惠券ID
local voucherId = ARGV[1]
--1.2 用户ID
local userId = ARGV[2]

--2.数据key
--2.1 库存key
local stockKey = 'seckill:voucher:' .. voucherId
--2.2 订单key
local orderKey = 'seckill:order:' .. voucherId


--3.脚本业务
--3.1 判断库存是否充足 get stockKey
if (tonumber(redis.call('get', stockKey)) <= 0) then
    --库存不足，返回1
    return 1
end
--3.2 判断用户是否下单(利用set集合中的isMember)
if redis.call('sismember', orderKey, userId) == 1 then
    --存在订单->为重复下单->返回2
    return 2
end
--3.3 说明库存充足+用户未下单->扣库存（incrby stockKey -1）
redis.call('incrby', stockKey, -1)
--3.4 下单->将userId存入当前优惠券set集合中
redis.call('sadd', orderKey, userId)
return 0