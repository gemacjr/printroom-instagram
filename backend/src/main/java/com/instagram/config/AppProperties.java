package com.instagram.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        Jwt jwt,
        Aws aws,
        Tables tables,
        Media media,
        Queues queues,
        Feed feed,
        Workers workers,
        Cors cors
) {
    public record Jwt(String secret, Duration ttl) {}

    public record Aws(String region, String endpoint, String publicEndpoint) {
        public boolean isLocal() {
            return endpoint != null && !endpoint.isBlank();
        }
    }

    public record Tables(String users, String usernames, String posts, String follows, String media) {}

    public record Media(
            String bucket,
            String cdnBaseUrl,
            Duration presignTtl,
            long maxImageBytes,
            long maxVideoBytes,
            long multipartThresholdBytes,
            long multipartPartBytes
    ) {}

    public record Queues(String mediaEvents, String feedFanout) {}

    public record Feed(
            long celebrityThreshold,
            int maxCachedEntries,
            int fanoutBatchSize,
            int defaultPageSize,
            int maxPageSize
    ) {}

    public record Workers(boolean enabled, int pollWaitSeconds) {}

    public record Cors(List<String> allowedOrigins) {}
}
