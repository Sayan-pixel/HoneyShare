package com.honeyshare.security;

import com.honeyshare.common.SecureTokens;
import com.honeyshare.identity.domain.User;
import com.honeyshare.identity.domain.UserRole;
import com.honeyshare.identity.domain.UserSession;
import com.honeyshare.identity.port.SessionRepository;
import com.honeyshare.identity.port.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * Reads the bearer token from the Authorization header, looks up the session
 * in the database, and populates the Spring Security context.
 *
 * <p>Token format: {@code Bearer <raw-token>}
 * The raw token is hashed before lookup; the hash is never logged.
 */
@Component
public class SessionAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SessionAuthFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final SessionRepository sessionRepository;
    private final UserRepository userRepository;

    public SessionAuthFilter(SessionRepository sessionRepository, UserRepository userRepository) {
        this.sessionRepository = sessionRepository;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain
    ) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        String rawToken = authHeader.substring(BEARER_PREFIX.length()).strip();
        if (rawToken.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }

        try {
            String tokenHash = SecureTokens.hash(rawToken);
            sessionRepository.findBySessionTokenHash(tokenHash).ifPresent(session -> {
                if (session.isValid()) {
                    userRepository.findById(session.getUserId()).ifPresent(user -> {
                        authenticateRequest(user, session, request);
                        // Touch last-used asynchronously to avoid DB write on every request
                        // (acceptable for MVP; upgrade to async or batch later)
                        session.recordUse(Instant.now());
                        sessionRepository.save(session);
                    });
                }
            });
        } catch (Exception e) {
            log.warn("Session authentication error for request [{}]: {}", request.getRequestURI(), e.getMessage());
            // Never propagate auth errors; let the authorization layer reject
        }

        chain.doFilter(request, response);
    }

    private void authenticateRequest(User user, UserSession session, HttpServletRequest request) {
        List<SimpleGrantedAuthority> authorities = List.of(
                new SimpleGrantedAuthority("ROLE_" + user.getRole().name())
        );

        // Custom principal carrying both user and session context
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(user, session);

        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(principal, null, authorities);
        auth.setDetails(request.getRemoteAddr());

        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}

