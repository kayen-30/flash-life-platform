-- 秒杀资格校验脚本：在 Redis 内原子完成库存判断、一人一单和预扣库存。
local stockKey = KEYS[1]
local orderKey = KEYS[2]
local userId = ARGV[1]

local stock = tonumber(redis.call('get', stockKey))
if stock == nil or stock <= 0 then
    return 1
end

if redis.call('sismember', orderKey, userId) == 1 then
    return 2
end

redis.call('incrby', stockKey, -1)
redis.call('sadd', orderKey, userId)
return 0
