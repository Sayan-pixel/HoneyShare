package com.honeyshare.storage;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedUploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.List;

/**
 * Storage adapter implementing StoragePort via AWS SDK v2.
 * Works seamlessly with MinIO (local dev) and AWS S3 / S3-compatible cloud storage.
 */
@Component
public class S3StorageAdapter implements StoragePort {

    private static final Logger log = LoggerFactory.getLogger(S3StorageAdapter.class);

    private final StorageProperties properties;
    private final S3Client s3Client;
    private final S3Presigner s3Presigner;

    public S3StorageAdapter(StorageProperties properties) {
        this.properties = properties;

        var credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(properties.getAccessKey(), properties.getSecretKey()));
        var region = Region.of(properties.getRegion());

        S3ClientBuilder clientBuilder = S3Client.builder()
                .region(region)
                .credentialsProvider(credentials);

        S3Presigner.Builder presignerBuilder = S3Presigner.builder()
                .region(region)
                .credentialsProvider(credentials);

        if (properties.getEndpoint() != null && !properties.getEndpoint().isBlank()) {
            URI endpointUri = URI.create(properties.getEndpoint());
            clientBuilder.endpointOverride(endpointUri);
            presignerBuilder.endpointOverride(endpointUri);
            // MinIO requires path-style access
            clientBuilder.serviceConfiguration(S3Configuration.builder()
                    .pathStyleAccessEnabled(true)
                    .build());
            presignerBuilder.serviceConfiguration(S3Configuration.builder()
                    .pathStyleAccessEnabled(true)
                    .build());
        }

        this.s3Client = clientBuilder.build();
        this.s3Presigner = presignerBuilder.build();
    }

    @PostConstruct
    public void ensureBucketExists() {
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(properties.getBucket()).build());
            log.info("Storage bucket '{}' verified.", properties.getBucket());
        } catch (NoSuchBucketException e) {
            log.info("Storage bucket '{}' does not exist. Creating it...", properties.getBucket());
            try {
                s3Client.createBucket(CreateBucketRequest.builder().bucket(properties.getBucket()).build());
                log.info("Storage bucket '{}' created successfully.", properties.getBucket());
            } catch (Exception createEx) {
                log.warn("Could not create bucket '{}': {}", properties.getBucket(), createEx.getMessage());
            }
        } catch (Exception e) {
            log.debug("Storage bucket verification note: {}", e.getMessage());
        }

        configureBucketCors();
    }

    /**
     * Applies CORS rules to the bucket to allow secure browser direct-to-S3 uploads.
     */
    public void configureBucketCors() {
        try {
            CORSRule rule = CORSRule.builder()
                    .allowedOrigins("*")
                    .allowedMethods("GET", "PUT", "POST", "HEAD")
                    .allowedHeaders("*")
                    .exposeHeaders("ETag", "Content-Range", "Accept-Ranges")
                    .maxAgeSeconds(3600)
                    .build();

            CORSConfiguration cors = CORSConfiguration.builder()
                    .corsRules(rule)
                    .build();

            s3Client.putBucketCors(PutBucketCorsRequest.builder()
                    .bucket(properties.getBucket())
                    .corsConfiguration(cors)
                    .build());
            log.info("CORS configuration applied to storage bucket '{}'.", properties.getBucket());
        } catch (Exception e) {
            log.debug("CORS configuration note for bucket '{}': {}", properties.getBucket(), e.getMessage());
        }
    }

    @Override
    public String initiateMultipartUpload(String objectKey, String contentType) {
        CreateMultipartUploadRequest req = CreateMultipartUploadRequest.builder()
                .bucket(properties.getBucket())
                .key(objectKey)
                .contentType(contentType != null ? contentType : "application/octet-stream")
                .build();

        CreateMultipartUploadResponse res = s3Client.createMultipartUpload(req);
        log.info("Initiated multipart upload: key={} uploadId={}", objectKey, res.uploadId());
        return res.uploadId();
    }

    @Override
    public String generatePresignedUploadPartUrl(String objectKey, String uploadId, int partNumber, Duration ttl) {
        UploadPartRequest uploadPartRequest = UploadPartRequest.builder()
                .bucket(properties.getBucket())
                .key(objectKey)
                .uploadId(uploadId)
                .partNumber(partNumber)
                .build();

        UploadPartPresignRequest presignRequest = UploadPartPresignRequest.builder()
                .signatureDuration(ttl)
                .uploadPartRequest(uploadPartRequest)
                .build();

        PresignedUploadPartRequest presigned = s3Presigner.presignUploadPart(presignRequest);
        log.debug("Issued presigned upload part URL: key={} part={}", objectKey, partNumber);
        return presigned.url().toString();
    }

    @Override
    public String uploadPart(String objectKey, String uploadId, int partNumber, InputStream data, long contentLength) {
        UploadPartRequest req = UploadPartRequest.builder()
                .bucket(properties.getBucket())
                .key(objectKey)
                .uploadId(uploadId)
                .partNumber(partNumber)
                .contentLength(contentLength)
                .build();

        UploadPartResponse res = s3Client.uploadPart(req, RequestBody.fromInputStream(data, contentLength));
        log.debug("Uploaded part via stream: key={} part={} eTag={}", objectKey, partNumber, res.eTag());
        return res.eTag();
    }

    @Override
    public String generatePresignedDownloadUrl(String objectKey, Duration ttl, String downloadFilename) {
        GetObjectRequest.Builder getReq = GetObjectRequest.builder()
                .bucket(properties.getBucket())
                .key(objectKey);

        if (downloadFilename != null && !downloadFilename.isBlank()) {
            getReq.responseContentDisposition("attachment; filename=\"" + downloadFilename.replace("\"", "") + "\"");
        }

        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .getObjectRequest(getReq.build())
                .build();

        PresignedGetObjectRequest presigned = s3Presigner.presignGetObject(presignRequest);
        // Never log the full presigned URL
        log.debug("Issued presigned download URL: key={}", objectKey);
        return presigned.url().toString();
    }

    @Override
    public String completeMultipartUpload(String objectKey, String uploadId, List<PartETag> parts) {
        List<CompletedPart> completedParts = parts.stream()
                .map(p -> CompletedPart.builder()
                        .partNumber(p.partNumber())
                        .eTag(p.etag())
                        .build())
                .toList();

        CompleteMultipartUploadRequest req = CompleteMultipartUploadRequest.builder()
                .bucket(properties.getBucket())
                .key(objectKey)
                .uploadId(uploadId)
                .multipartUpload(CompletedMultipartUpload.builder().parts(completedParts).build())
                .build();

        CompleteMultipartUploadResponse res = s3Client.completeMultipartUpload(req);
        log.info("Completed multipart upload: key={} eTag={}", objectKey, res.eTag());
        return res.eTag();
    }

    @Override
    public void abortMultipartUpload(String objectKey, String uploadId) {
        try {
            AbortMultipartUploadRequest req = AbortMultipartUploadRequest.builder()
                    .bucket(properties.getBucket())
                    .key(objectKey)
                    .uploadId(uploadId)
                    .build();
            s3Client.abortMultipartUpload(req);
            log.info("Aborted multipart upload: key={} uploadId={}", objectKey, uploadId);
        } catch (Exception e) {
            log.warn("Failed to abort multipart upload: key={} uploadId={}, error={}",
                    objectKey, uploadId, e.getMessage());
        }
    }

    @Override
    public boolean deleteObject(String objectKey) {
        try {
            DeleteObjectRequest req = DeleteObjectRequest.builder()
                    .bucket(properties.getBucket())
                    .key(objectKey)
                    .build();
            s3Client.deleteObject(req);
            log.info("Deleted object from storage: key={}", objectKey);
            return true;
        } catch (Exception e) {
            log.error("Failed to delete object: key={}, error={}", objectKey, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean objectExists(String objectKey) {
        try {
            HeadObjectRequest req = HeadObjectRequest.builder()
                    .bucket(properties.getBucket())
                    .key(objectKey)
                    .build();
            s3Client.headObject(req);
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (Exception e) {
            log.warn("Error checking object existence: key={}, error={}", objectKey, e.getMessage());
            return false;
        }
    }

    @Override
    public long getObjectSize(String objectKey) {
        HeadObjectRequest req = HeadObjectRequest.builder()
                .bucket(properties.getBucket())
                .key(objectKey)
                .build();
        HeadObjectResponse res = s3Client.headObject(req);
        return res.contentLength();
    }

    @Override
    public InputStream openObjectStream(String objectKey) {
        GetObjectRequest req = GetObjectRequest.builder()
                .bucket(properties.getBucket())
                .key(objectKey)
                .build();
        return s3Client.getObject(req);
    }

    @Override
    public InputStream openObjectRangeStream(String objectKey, long startByte, long endByte) {
        GetObjectRequest req = GetObjectRequest.builder()
                .bucket(properties.getBucket())
                .key(objectKey)
                .range("bytes=" + startByte + "-" + endByte)
                .build();
        return s3Client.getObject(req);
    }
}

