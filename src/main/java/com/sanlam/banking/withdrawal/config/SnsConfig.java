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

/**
 * The original snippet built an SnsClient in the controller's constructor.
 * That makes the client untestable, unshareable, never closed, and ties a web
 * component to an AWS transport concern.
 *
 * SnsClient is thread-safe and expensive to create (it owns an HTTP connection
 * pool), so exactly one is created here and managed by the container, which
 * also closes it on shutdown.
 */
@Configuration
@RequiredArgsConstructor
public class SnsConfig {

    private final AwsProperties awsProperties;

    @Bean
    public SnsClient snsClient() {
        SnsClientBuilder builder = SnsClient.builder()
                .region(Region.of(awsProperties.region()));

        // Present only for the local LocalStack environment; in a real deployment
        // the endpoint and credentials come from the default AWS resolution chain
        // (instance role / IRSA / environment), never from configuration files.
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
