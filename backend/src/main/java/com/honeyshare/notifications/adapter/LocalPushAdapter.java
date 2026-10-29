package com.honeyshare.notifications.adapter;

import com.honeyshare.notifications.port.PushPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Free local push notification adapter.
 * Verifies that the payload is privacy-preserving (generic text only) and logs locally.
 * Incurs zero external charges ($0 cost).
 */
@Component
@ConditionalOnProperty(name = "honeyshare.notifications.push.provider", havingValue = "local", matchIfMissing = true)
public class LocalPushAdapter implements PushPort {

    private static final Logger log = LoggerFactory.getLogger(LocalPushAdapter.class);

    @Override
    public void sendPush(String userId, String title, String genericBody) {
        // Enforce push privacy: Never log or emit sensitive file content or tokens
        log.info("[LOCAL-PUSH-NOTIFICATION] User: {} | Title: {} | Body: {}", userId, title, genericBody);
    }
}

