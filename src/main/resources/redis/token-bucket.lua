-- Token bucket rate limit on the Redis clock.
local capacity = tonumber(ARGV[1])
local rate = tonumber(ARGV[2])

local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)

local state = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(state[1])
local updated = tonumber(state[2])
if not tokens or not updated then
  tokens = capacity
  updated = now
end
if now > updated then
  tokens = math.min(capacity, tokens + (now - updated) * rate / 1000)
end

local allowed = 0
local retryAfter = 0
if tokens >= 1 then
  tokens = tokens - 1
  allowed = 1
else
  retryAfter = math.ceil((1 - tokens) * 1000 / rate)
end

redis.call('HSET', KEYS[1], 'tokens', string.format('%.17g', tokens), 'ts', string.format('%d', now))
redis.call('PEXPIRE', KEYS[1], string.format('%d', math.ceil(capacity * 1000 / rate) + 1000))
return {allowed, retryAfter, math.floor(tokens)}
