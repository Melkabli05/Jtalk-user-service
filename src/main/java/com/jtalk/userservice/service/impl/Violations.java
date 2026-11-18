package com.jtalk.userservice.service.impl;

import java.sql.SQLException;

/** Recognises which database constraint a failed write broke, so a race between two requests still ends in the right problem. */
final class Violations {

    private static final String UNIQUE_VIOLATION = "23505";

    private Violations() {
    }

    /** Whether the failure is a unique-constraint violation on the named constraint or index. */
    static boolean isUnique(Throwable failure, String constraint) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && UNIQUE_VIOLATION.equals(sql.getSQLState())
                    && sql.getMessage() != null && sql.getMessage().contains(constraint)) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }
}
