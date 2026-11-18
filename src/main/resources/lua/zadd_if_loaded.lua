-- Adds a member to a cached follow graph only if that graph is in Redis.
--
-- A graph that is not there is rebuilt from PostgreSQL the next time it is read, and that rebuild already includes this follow, so adding
-- to a missing key would only create a set that looks complete but holds one member. NX keeps an existing member's original timestamp.
--
-- KEYS[1] the sorted set   ARGV[1] score (the follow's epoch millis)   ARGV[2] member (the other user's id)
-- Returns 1 if the member was added, 0 otherwise.
if redis.call('EXISTS', KEYS[1]) == 1 then
    return redis.call('ZADD', KEYS[1], 'NX', ARGV[1], ARGV[2])
end
return 0
