-- 优惠券ID
local voucherId = ARGV[1]
-- 用户ID
local userId = ARGV[2]
-- 库存Key
local stockKey = 'seckill:stock:' .. voucherId
-- 订单Key
local orderKey = 'seckill:order:' .. voucherId
-- 判断库存是否充足
if(tonumber(redis.call('get',stockKey)) <= 0) then
    -- 库存不足
    return 1
end
if(redis.call('sismember',orderKey,userId) == 1) then
    -- 限购一单
    return 2
end
-- 扣库存
redis.call('incrby',stockKey,-1)
-- 保存下单用户ID
redis.call("sadd",orderKey,userId)
return 0
