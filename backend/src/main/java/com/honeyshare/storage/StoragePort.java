package com.honeyshare.storage;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;

/**
 * Storage port interface abstracting S3-compatible object storage operations.
 * Decouples domain logic from vendor-specific storage SDKs.
 */
public interface StoragePort {

    /**
     * Start a multipart upload on the object store.
     *
     * @param objectKey   the destination key in the bucket
     * @param contentType MIME type hint (or application/octet-stream)
     * @return storage upload ID
     */
    String initiateMultipartUpload(String objectKey, String contentType);

    /**
     * Issue a short-lived pre-signed URL for a client to upload a specific part.
     *
     * @param objectKey  object key
     * @param uploadId   multipart upload ID
     * @param partNumber 1-based part number
     * @param ttl        validity duration
     * @return pre-signed HTTPS upload URL
     */
    String generatePresignedUploadPartUrl(String objectKey, String uploadId, int partNumber, Duration ttl);

    /**
     * Upload an individual part to a multipart upload directly via server stream.
     *
     * @param objectKey     destination key
     * @param uploadId      multipart upload ID
     * @param partNumber    1-based part number
     * @param data          binary input stream
     * @param contentLength exact byte length
     * @return the part's ETag
     */
    String uploadPart(String objectKey, String uploadId, int partNumber, InputStream data, long contentLength);

    /**
     * Issue a short-lived pre-signed URL for a client to download the object.
     *
     * @param objectKey        object key
     * @param ttl              validity duration
     * @param downloadFilename optional filename for Content-Disposition header
     * @return pre-signed HTTPS download URL
     */
    String generatePresignedDownloadUrl(String objectKey, Duration ttl, String downloadFilename);

    /**
     * Finalize the multipart upload, assembling all committed parts into a single object.
     *
     * @param objectKey object key
     * @param uploadId  multipart upload ID
     * @param parts     ordered list of parts and their ETags
     * @return the assembled object's ETag
     */
    String completeMultipartUpload(String objectKey, String uploadId, List<PartETag> parts);

    /**
     * Cancel an active multipart upload and clean up temporary parts.
     */
    void abortMultipartUpload(String objectKey, String uploadId);

    /**
     * Delete an object from the bucket.
     *
     * @return true if deleted or object did not exist; false on storage failure
     */
    boolean deleteObject(String objectKey);

    /**
     * Check if an object exists in storage.
     */
    boolean objectExists(String objectKey);

    /**
     * Get the exact byte size of an object in storage.
     */
    long getObjectSize(String objectKey);

    /**
     * Stream the object bytes (for server-side streaming SHA-256 verification).
     * Do NOT buffer the stream into memory.
     */
    InputStream openObjectStream(String objectKey);

    /**
     * Stream a specific byte range of the object (RFC 7233 partial content).
     *
     * @param objectKey destination key
     * @param startByte inclusive start byte offset
     * @param endByte   inclusive end byte offset
     * @return binary input stream for the specified range
     */
    default InputStream openObjectRangeStream(String objectKey, long startByte, long endByte) {
        return openObjectStream(objectKey);
    }
}

