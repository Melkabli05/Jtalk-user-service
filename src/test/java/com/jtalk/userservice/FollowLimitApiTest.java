package com.jtalk.userservice;

import com.jtalk.userservice.support.ApiTestBase;
import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Property(name = "user.max-following", value = "2")
class FollowLimitApiTest extends ApiTestBase {

    @Test
    void followingMoreThanTheLimitIsRefused_butRepeatsAndUnfollowsStillWork() {
        for (String name : new String[]{"alice", "bob", "carol", "dave"}) {
            register(name);
        }
        follow("alice", "bob");
        follow("alice", "carol");

        assertProblem(HttpStatus.UNPROCESSABLE_ENTITY, "follow-limit-reached", () -> follow("alice", "dave"));
        assertEquals(HttpStatus.NO_CONTENT, follow("alice", "bob").status(), "someone already followed is not a new follow");

        unfollow("alice", "bob");
        assertEquals(HttpStatus.NO_CONTENT, follow("alice", "dave").status(), "room again once someone is unfollowed");
    }
}
