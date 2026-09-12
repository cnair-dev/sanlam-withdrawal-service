package com.sanlam.banking.withdrawal.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Replaces the hardcoded region and "arn:aws:sns:YOUR_REGION:..." string from the original
 * snippet. Bound and validated at startup rather than read through scattered @Value
 * lookups, so a blank topic ARN fails the context instead of the first withdrawal.
 */
@Validated
@ConfigurationProperties(prefix = "app.aws")
public record AwsProperties(
        @NotBlank String region,
        @NotBlank String topicArn,
        String endpointOverride,
        String accessKey,
        String secretKey
) {}
