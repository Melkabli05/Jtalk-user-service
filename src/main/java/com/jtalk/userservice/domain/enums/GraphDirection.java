package com.jtalk.userservice.domain.enums;

/** Which side of the follow graph a set describes, from the point of view of its owner. */
public enum GraphDirection {
    /** The accounts the owner follows. */
    FOLLOWING,
    /** The accounts that follow the owner. */
    FOLLOWERS
}
