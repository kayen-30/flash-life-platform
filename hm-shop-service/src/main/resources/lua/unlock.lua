---@diagnostic disable: undefined-global
-- 校验锁归属后再删除，保证释放锁的判断和删除在 Redis 中原子执行。
local redis_call = redis.call
local lock_key = KEYS[1]
local lock_value = ARGV[1]

if redis_call('get', lock_key) == lock_value then
    return redis_call('del', lock_key)
end

return 0
