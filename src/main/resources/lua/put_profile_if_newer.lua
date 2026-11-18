-- Caches a profile hash unless Redis already holds a copy that is as new or newer.
--
-- A slow reader that loaded a profile before it was changed can reach Redis after the writer has already stored the new one; without
-- this check it would put the old one back for a day. Versions are the profile's updatedAt in epoch microseconds.
--
-- KEYS[1] the profile hash   ARGV[1] ttl in seconds   ARGV[2] version   ARGV[3..] field, value, field, value...
-- Returns 1 if the profile was stored, 0 if the cached copy was kept.
local current = redis.call('HGET', KEYS[1], 'version')
if current and tonumber(current) >= tonumber(ARGV[2]) then
    return 0
end
-- Replace, not merge: a field that was cleared (the avatar) must not survive from the old copy.
redis.call('DEL', KEYS[1])
redis.call('HSET', KEYS[1], unpack(ARGV, 3))
redis.call('EXPIRE', KEYS[1], ARGV[1])
return 1
