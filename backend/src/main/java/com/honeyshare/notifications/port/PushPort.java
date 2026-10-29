package com.honeyshare.notifications.port;

/**
 * Port interface for sending push notifications.
 * Decouples domain logic from vendor-specific push gateways (e.g. APNs, FCM, WebPush).
 */
public interface PushPort {
    void sendPush(String userId, String title, String genericBody);
}

