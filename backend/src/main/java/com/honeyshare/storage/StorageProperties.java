package com.honeyshare.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "honeyshare.storage")
public class StorageProperties {

    private String provider = "minio";
    private String endpoint = "http://localhost:9000";
    private String region = "us-east-1";
    private String bucket = "honeyshare-files";
    private String accessKey = "minioadmin";
    private String secretKey = "minioadmin";
    private int presignedUrlLifetimeSeconds = 300;
    private int multipartUploadLifetimeHours = 48;

    public String getProvider() { return provider; }
    public void setProvider(String v) { this.provider = v; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String v) { this.endpoint = v; }
    public String getRegion() { return region; }
    public void setRegion(String v) { this.region = v; }
    public String getBucket() { return bucket; }
    public void setBucket(String v) { this.bucket = v; }
    public String getAccessKey() { return accessKey; }
    public void setAccessKey(String v) { this.accessKey = v; }
    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String v) { this.secretKey = v; }
    public int getPresignedUrlLifetimeSeconds() { return presignedUrlLifetimeSeconds; }
    public void setPresignedUrlLifetimeSeconds(int v) { this.presignedUrlLifetimeSeconds = v; }
    public int getMultipartUploadLifetimeHours() { return multipartUploadLifetimeHours; }
    public void setMultipartUploadLifetimeHours(int v) { this.multipartUploadLifetimeHours = v; }
}

