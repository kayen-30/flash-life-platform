-- 只允许当前订单释放自己的购买资格，避免迟到的旧消息误回滚用户的新预约。
if redis.call('hget', KEYS[3], 'id') ~= ARGV[2] then
    return 0
end

local rolledBack = 0
if redis.call('hget', KEYS[2], ARGV[1]) == ARGV[2] then
    redis.call('hdel', KEYS[2], ARGV[1])
    redis.call('incrby', KEYS[1], 1)
    rolledBack = 1
elseif redis.call('srem', KEYS[5], ARGV[1]) == 1 then
    redis.call('incrby', KEYS[1], 1)
    rolledBack = 1
end
redis.call('del', KEYS[3])
redis.call('zrem', KEYS[4], ARGV[2])
return rolledBack
