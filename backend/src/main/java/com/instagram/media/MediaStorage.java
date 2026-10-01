package com.instagram.media;

import com.instagram.config.AppProperties;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/** Thin wrapper over S3 so services can be unit tested without the SDK. */
@Component
public class MediaStorage {
    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;
    private final Duration presignTtl;

    public MediaStorage(S3Client s3, S3Presigner presigner, AppProperties props) {
        this.s3 = s3;
        this.presigner = presigner;
        this.bucket = props.media().bucket();
        this.presignTtl = props.media().presignTtl();
    }

    /** Presigned PUT that pins Content-Type and Content-Length so the client can't upload something else. */
    public String presignPut(String key, String contentType, long contentLength) {
        return presigner.presignPutObject(p -> p
                .signatureDuration(presignTtl)
                .putObjectRequest(r -> r.bucket(bucket).key(key)
                        .contentType(contentType)
                        .contentLength(contentLength)))
                .url().toString();
    }

    public String createMultipartUpload(String key, String contentType) {
        return s3.createMultipartUpload(r -> r.bucket(bucket).key(key).contentType(contentType)).uploadId();
    }

    public String presignUploadPart(String key, String uploadId, int partNumber) {
        return presigner.presignUploadPart(p -> p
                .signatureDuration(presignTtl)
                .uploadPartRequest(r -> r.bucket(bucket).key(key).uploadId(uploadId).partNumber(partNumber)))
                .url().toString();
    }

    public void completeMultipartUpload(String key, String uploadId, List<MediaDtos.CompletedPart> parts) {
        var completed = parts.stream()
                .map(p -> CompletedPart.builder().partNumber(p.partNumber()).eTag(p.eTag()).build())
                .toList();
        s3.completeMultipartUpload(r -> r.bucket(bucket).key(key).uploadId(uploadId)
                .multipartUpload(CompletedMultipartUpload.builder().parts(completed).build()));
    }

    public void abortMultipartUpload(String key, String uploadId) {
        s3.abortMultipartUpload(r -> r.bucket(bucket).key(key).uploadId(uploadId));
    }

    public byte[] getBytes(String key) {
        return s3.getObjectAsBytes(r -> r.bucket(bucket).key(key)).asByteArray();
    }

    public void put(String key, byte[] bytes, String contentType) {
        s3.putObject(r -> r.bucket(bucket).key(key).contentType(contentType)
                        .cacheControl("public, max-age=31536000, immutable"),
                RequestBody.fromBytes(bytes));
    }

    public void deleteAll(Collection<String> keys) {
        var ids = keys.stream().filter(Objects::nonNull).distinct()
                .map(k -> ObjectIdentifier.builder().key(k).build()).toList();
        if (ids.isEmpty()) return;
        s3.deleteObjects(r -> r.bucket(bucket).delete(Delete.builder().objects(ids).quiet(true).build()));
    }
}
