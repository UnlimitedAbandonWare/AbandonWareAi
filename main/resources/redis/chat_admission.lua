-- KEYS share a cluster hash tag. Only hashes, counters and an opaque claim token are stored.
-- ARGV: userCapacity,userRatePerSecond,ipCapacity,ipRatePerSecond,fingerprint,claim,useIdempotency,ttlMs
if ARGV[7] == '1' then
    local prior = redis.call('HGET', KEYS[3], 'fingerprint')
    if prior then
        if prior == ARGV[5] then return {2, 0} else return {3, 0} end
    end
end
local stamp = redis.call('TIME')
local now = tonumber(stamp[1]) * 1000 + math.floor(tonumber(stamp[2]) / 1000)
local available = {}
local rates = {tonumber(ARGV[2]), tonumber(ARGV[4])}
local capacities = {tonumber(ARGV[1]), tonumber(ARGV[3])}
local retry = 0
for i = 1, 2 do
    local row = redis.call('HMGET', KEYS[i], 'tokens', 'at')
    local tokens = tonumber(row[1]) or capacities[i]
    local at = tonumber(row[2]) or now
    tokens = math.min(capacities[i], tokens + math.max(0, now - at) * rates[i] / 1000)
    available[i] = tokens
    if tokens < 1 then retry = math.max(retry, math.ceil((1 - tokens) * 1000 / rates[i])) end
end
-- Check every bucket before decrementing any; a denied IP does not consume user capacity.
for i = 1, 2 do
    local remaining = available[i]
    if retry == 0 then remaining = remaining - 1 end
    redis.call('HSET', KEYS[i], 'tokens', tostring(remaining), 'at', tostring(now))
    redis.call('PEXPIRE', KEYS[i], math.ceil(capacities[i] * 1000 / rates[i]) + 1000)
end
if retry > 0 then return {0, retry} end
if ARGV[7] == '1' then
    redis.call('HSET', KEYS[3], 'fingerprint', ARGV[5], 'claim', ARGV[6])
    redis.call('PEXPIRE', KEYS[3], ARGV[8])
end
return {1, 0}
