-- KEYS[1] = stock key        (e.g. flash:stock:{itemId})
-- KEYS[2] = buyers set key   (e.g. flash:buyers:{itemId})
-- ARGV[1] = userId
--
-- Return codes:
--   1  = success (stock decremented, user recorded)
--   0  = sold out
--  -1  = sale not initialized (stock key missing)
--  -2  = user already bought this item

if redis.call('EXISTS', KEYS[1]) == 0 then
    return -1
end

if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
    return -2
end

local stock = tonumber(redis.call('GET', KEYS[1]))
if stock <= 0 then
    return 0
end

redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])
return 1