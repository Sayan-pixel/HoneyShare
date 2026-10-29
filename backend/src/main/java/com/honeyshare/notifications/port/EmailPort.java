package com.honeyshare.notifications.port;

/**
 * Port interface for sending email notifications.
 * Decouples domain logic from vendor-specific email providers.
 */
public interface EmailPort {
    void sendEmail(String toEmail, String subject, String bodyText);
}

