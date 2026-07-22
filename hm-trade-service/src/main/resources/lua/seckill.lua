-- 秒杀资格校验和库存预扣必须在同一个 Lua 脚本中原子完成。
local stockKey = KEYS[1]
local orderKey = KEYS[2]
local pendingOrderKey = KEYS[3]
local pendingOrderIndexKey = KEYS[4]
local userId = ARGV[1]
local orderId = ARGV[2]
local voucherId = ARGV[3]
local createdAt = ARGV[4]
local keyTtlSeconds = tonumber(ARGV[5])

local stock = tonumber(redis.call('get', stockKey))
if stock == nil or stock <= 0 then
    return 1
end

if redis.call('sismember', orderKey, userId) == 1 then
    return 2
end

redis.call('incrby', stockKey, -1)
redis.call('sadd', orderKey, userId)
-- 每次成功预扣都刷新为活动结束后的固定补偿窗口，历史活动 key 会自动回收。
redis.call('expire', stockKey, keyTtlSeconds)
redis.call('expire', orderKey, keyTtlSeconds)
-- 待发布记录与库存预扣同脚本写入，进程崩溃后可由定时任务重新投递 RabbitMQ。
redis.call('hset', pendingOrderKey, 'id', orderId, 'userId', userId, 'voucherId', voucherId)
redis.call('zadd', pendingOrderIndexKey, createdAt, orderId)
return 0
