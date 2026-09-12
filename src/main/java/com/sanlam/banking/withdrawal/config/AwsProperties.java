package com.sanlam.banking.withdrawal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Replaces the hardcoded region and "arn:aws:sns:YOUR_REGION:..." string from
 * the original snippet. Bound and validated at startup via @ConfigurationProperties
 * rather than scattered @Value lookups, so a missing topic ARN fails the
 * application context instead of failing the first withdrawal at runtime.
 */
@ConfigurationProperties(prefix = "app.aws")
public record AwsProperties(
        String region,
        String topicArn,
        String endpointOverride,
        String accessKey,
        String secretKey
) {}
