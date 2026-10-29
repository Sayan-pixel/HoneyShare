package com.honeyshare.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "honeyshare.features")
public class FeatureProperties {

    /**
     * Feature flag controlling End-to-End Encryption (E2E) endpoints.
     * Default: false (E2E mode disabled pending protocol review).
     */
    private boolean e2eEnabled = false;

    public boolean isE2eEnabled() {
        return e2eEnabled;
    }

    public void setE2eEnabled(boolean e2eEnabled) {
        this.e2eEnabled = e2eEnabled;
    }
}

