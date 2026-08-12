-- 超时取消专用：不依赖 Pending Hash，直接回补库存和购买资格。
-- 库存 key 已过期说明活动早已结束，无需回补。
if redis.call('exists', KEYS[1]) == 0 then
    return 0
end
if redis.call('hget', KEYS[2], ARGV[1]) == ARGV[2] then
    redis.call('hdel', KEYS[2], ARGV[1])
    redis.call('incrby', KEYS[1], 1)
    return 1
end
if redis.call('srem', KEYS[3], ARGV[1]) == 1 then
    redis.call('incrby', KEYS[1], 1)
    return 1
end
-- 当前资格不属于这张订单（已回补或已重新抢购），幂等返回 0。
return 0
