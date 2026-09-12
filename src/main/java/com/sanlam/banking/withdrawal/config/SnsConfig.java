package com.sanlam.banking.withdrawal.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.SnsClientBuilder;

import java.net.URI;
import java.time.Duration;

/**
 * The original snippet built an SnsClient in the controller's constructor: untestable,
 * unshareable, never closed, and a web component tied to an AWS transport concern.
 * SnsClient is thread-safe and expensive to create - it owns an HTTP connection pool - so
 * exactly one is built here and closed by the container on shutdown.
 */
@Configuration
@RequiredArgsConstructor
public class SnsConfig {

    private final AwsProperties awsProperties;

    @Bean
    public SnsClient snsClient() {
        SnsClientBuilder builder = SnsClient.builder()
                .region(Region.of(awsProperties.region()))

                // The relay publishes a batch inside one transaction, so the time a call
                // can take is the time row locks and a pooled connection are held. The SDK
                // sets no API call timeout by default - only a 30s socket read, retried
                // three times - so an endpoint that hangs rather than refuses would stall
                // a drain for batch-size times that. Bounded here rather than inherited.
                .overrideConfiguration(c -> c
                        .apiCallTimeout(Duration.ofSeconds(10))
                        .apiCallAttemptTimeout(Duration.ofSeconds(3)));

        // LocalStack only. In a real deployment the endpoint and credentials come from
        // the default AWS resolution chain (instance role / IRSA), never from config.
        if (StringUtils.hasText(awsProperties.endpointOverride())) {
            builder.endpointOverride(URI.create(awsProperties.endpointOverride()))
                   .credentialsProvider(StaticCredentialsProvider.create(
                           AwsBasicCredentials.create(awsProperties.accessKey(), awsProperties.secretKey())));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }
        return builder.build();
    }
}
