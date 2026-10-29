package com.honeyshare.security;

import com.honeyshare.identity.domain.User;
import com.honeyshare.identity.domain.UserRole;
import com.honeyshare.identity.domain.UserSession;

/**
 * The authenticated principal stored in the Spring Security context.
 * Carries both the {@link User} and the active {@link UserSession}.
 */
public record AuthenticatedPrincipal(User user, UserSession session) {

    public String userId() {
        return user.getId();
    }

    public String sessionId() {
        return session.getId();
    }

    public boolean isAdmin() {
        return user.getRole() == UserRole.ADMIN;
    }

    public boolean isMfaVerified() {
        return session.isMfaVerified();
    }

    public boolean isEmailVerified() {
        return user.isEmailVerified();
    }
}

