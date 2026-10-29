package com.honeyshare.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "honeyshare")
public class TransferProperties {

    private Upload upload = new Upload();
    private Quota quota = new Quota();
    private Retention retention = new Retention();

    public static class Upload {
        private long chunkSizeBytes = 10485760L;       // 10 MB
        private int maxConcurrentParts = 4;
        private long maxFileSizeBytes = 107374182400L; // 100 GB

        public long getChunkSizeBytes() { return chunkSizeBytes; }
        public void setChunkSizeBytes(long v) { this.chunkSizeBytes = v; }
        public int getMaxConcurrentParts() { return maxConcurrentParts; }
        public void setMaxConcurrentParts(int v) { this.maxConcurrentParts = v; }
        public long getMaxFileSizeBytes() { return maxFileSizeBytes; }
        public void setMaxFileSizeBytes(long v) { this.maxFileSizeBytes = v; }
    }

    public static class Quota {
        private long defaultUserBytes = 214748364800L; // 200 GB

        public long getDefaultUserBytes() { return defaultUserBytes; }
        public void setDefaultUserBytes(long v) { this.defaultUserBytes = v; }
    }

    public static class Retention {
        private int defaultDurationHours = 24;
        private int maxDurationHours = 168;
        private int activeDownloadGraceHours = 24;

        public int getDefaultDurationHours() { return defaultDurationHours; }
        public void setDefaultDurationHours(int v) { this.defaultDurationHours = v; }
        public int getMaxDurationHours() { return maxDurationHours; }
        public void setMaxDurationHours(int v) { this.maxDurationHours = v; }
        public int getActiveDownloadGraceHours() { return activeDownloadGraceHours; }
        public void setActiveDownloadGraceHours(int v) { this.activeDownloadGraceHours = v; }
    }

    public Upload getUpload() { return upload; }
    public void setUpload(Upload upload) { this.upload = upload; }
    public Quota getQuota() { return quota; }
    public void setQuota(Quota quota) { this.quota = quota; }
    public Retention getRetention() { return retention; }
    public void setRetention(Retention retention) { this.retention = retention; }
}

