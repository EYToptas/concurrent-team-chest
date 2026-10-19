-- Stores a team view only if the cache has nothing newer.
local version = tonumber(ARGV[1])
local stored = redis.call('HMGET', KEYS[1], 'v', 'd')
local storedVersion = tonumber(stored[1])

if storedVersion then
  local isTombstone = not stored[2]
  if storedVersion > version or (storedVersion == version and not isTombstone) then
    return 0
  end
end

redis.call('HSET', KEYS[1], 'v', ARGV[1], 'd', ARGV[2])
redis.call('PEXPIRE', KEYS[1], ARGV[3])
return 1
