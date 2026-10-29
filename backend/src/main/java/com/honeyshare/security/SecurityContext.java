package com.honeyshare.security;

import com.honeyshare.common.exception.AuthenticationException;
import com.honeyshare.common.exception.MfaRequiredException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Utility for extracting the authenticated principal from the security context.
 * Use in service layers that need the current user.
 */
public final class SecurityContext {

    private SecurityContext() {}

    /**
     * Returns the current authenticated principal or throws {@link AuthenticationException}.
     */
    public static AuthenticatedPrincipal requirePrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof AuthenticatedPrincipal p)) {
            throw new AuthenticationException("Authentication required.");
        }
        return p;
    }

    /**
     * Returns the current principal and asserts MFA was verified for admin operations.
     */
    public static AuthenticatedPrincipal requireMfaVerified() {
        AuthenticatedPrincipal p = requirePrincipal();
        if (p.isAdmin() && !p.isMfaVerified()) {
            throw new MfaRequiredException();
        }
        return p;
    }
}

