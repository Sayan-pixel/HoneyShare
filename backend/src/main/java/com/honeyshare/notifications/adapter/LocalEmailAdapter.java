package com.honeyshare.notifications.adapter;

import com.honeyshare.notifications.port.EmailPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Free local email adapter.
 * Formats and outputs emails to application logs (or local SMTP test catcher).
 * Incurs zero external charges ($0 cost).
 */
@Component
@ConditionalOnProperty(name = "honeyshare.notifications.email.provider", havingValue = "local", matchIfMissing = true)
public class LocalEmailAdapter implements EmailPort {

    private static final Logger log = LoggerFactory.getLogger(LocalEmailAdapter.class);

    @Override
    public void sendEmail(String toEmail, String subject, String bodyText) {
        log.info("[LOCAL-EMAIL-OUTBOX] To: {} | Subject: {} | Body: {}", toEmail, subject, bodyText);
    }
}

