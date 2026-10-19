-- Replaces a cached team view with a tombstone of the committed version.
local version = tonumber(ARGV[1])
local storedVersion = tonumber(redis.call('HGET', KEYS[1], 'v'))

if storedVersion and storedVersion >= version then
  return 0
end

redis.call('DEL', KEYS[1])
redis.call('HSET', KEYS[1], 'v', ARGV[1])
redis.call('PEXPIRE', KEYS[1], ARGV[2])
return 1
