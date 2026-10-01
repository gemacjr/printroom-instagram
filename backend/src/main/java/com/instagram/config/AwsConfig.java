package com.instagram.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.net.URI;

/**
 * One configuration for LocalStack and real AWS. When {@code app.aws.endpoint} is set we point every
 * client at LocalStack with dummy credentials; otherwise the SDK's default region/credential chain is used
 * (ECS task role in prod).
 */
@Configuration
public class AwsConfig {

    private final AppProperties.Aws aws;

    public AwsConfig(AppProperties props) {
        this.aws = props.aws();
    }

    @Bean
    AwsCredentialsProvider awsCredentialsProvider() {
        return aws.isLocal()
                ? StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))
                : DefaultCredentialsProvider.builder().build();
    }

    @Bean
    DynamoDbClient dynamoDbClient(AwsCredentialsProvider creds) {
        var builder = DynamoDbClient.builder()
                .region(Region.of(aws.region()))
                .credentialsProvider(creds)
                .httpClient(UrlConnectionHttpClient.create());
        if (aws.isLocal()) builder.endpointOverride(URI.create(aws.endpoint()));
        return builder.build();
    }

    @Bean
    DynamoDbEnhancedClient dynamoDbEnhancedClient(DynamoDbClient client) {
        return DynamoDbEnhancedClient.builder().dynamoDbClient(client).build();
    }

    @Bean
    S3Client s3Client(AwsCredentialsProvider creds) {
        var builder = S3Client.builder()
                .region(Region.of(aws.region()))
                .credentialsProvider(creds)
                .httpClient(UrlConnectionHttpClient.create());
        if (aws.isLocal()) {
            builder.endpointOverride(URI.create(aws.endpoint()))
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
        }
        return builder.build();
    }

    /** Presigned URLs are consumed by the browser, so locally they must use the host-visible endpoint. */
    @Bean
    S3Presigner s3Presigner(AwsCredentialsProvider creds) {
        var builder = S3Presigner.builder()
                .region(Region.of(aws.region()))
                .credentialsProvider(creds);
        if (aws.isLocal()) {
            String endpoint = aws.publicEndpoint() == null || aws.publicEndpoint().isBlank()
                    ? aws.endpoint() : aws.publicEndpoint();
            builder.endpointOverride(URI.create(endpoint))
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
        }
        return builder.build();
    }

    @Bean
    SqsClient sqsClient(AwsCredentialsProvider creds) {
        var builder = SqsClient.builder()
                .region(Region.of(aws.region()))
                .credentialsProvider(creds)
                .httpClient(UrlConnectionHttpClient.create());
        if (aws.isLocal()) builder.endpointOverride(URI.create(aws.endpoint()));
        return builder.build();
    }
}
