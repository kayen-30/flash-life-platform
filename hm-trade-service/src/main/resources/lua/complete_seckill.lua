redis.call('del', KEYS[1])
redis.call('zrem', KEYS[2], ARGV[1])
return 1
