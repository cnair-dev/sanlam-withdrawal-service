package com.sanlam.banking.withdrawal.messaging;

import com.sanlam.banking.withdrawal.config.AwsProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;
import software.amazon.awssdk.services.sns.model.InvalidParameterException;
import software.amazon.awssdk.services.sns.model.InvalidParameterValueException;
import software.amazon.awssdk.services.sns.model.ValidationException;

import java.util.Map;

@Component
@Slf4j
@RequiredArgsConstructor
public class SnsEventPublisher implements EventPublisher {

    private final SnsClient snsClient;
    private final AwsProperties awsProperties;

    /**
     * A standard (not FIFO) SNS topic is used deliberately: this is a fan-out
     * notification to independent consumers that each tolerate duplicates, so
     * the higher throughput and lower cost of standard delivery is worth more
     * than FIFO's ordering and deduplication guarantees.
     *
     * eventType is set as a message ATTRIBUTE as well as being in the body so
     * subscribers can apply SNS filter policies and avoid paying to receive and
     * parse messages they do not want.
     */
    @Override
    public void publish(String eventType, String payload, String subject) {
        PublishRequest request = PublishRequest.builder()
                .topicArn(awsProperties.topicArn())
                .message(payload)
                .subject(subject)
                .messageAttributes(Map.of(
                        "eventType", MessageAttributeValue.builder()
                                .dataType("String").stringValue(eventType).build()))
                .build();

        try {
            PublishResponse response = snsClient.publish(request);
            log.debug("Published event to SNS: type={} messageId={}", eventType, response.messageId());
        } catch (InvalidParameterException | InvalidParameterValueException | ValidationException e) {
            // The message itself was rejected - malformed, oversized, or an
            // attribute SNS will not accept. No number of retries changes that.
            throw new EventPublishException(EventPublishException.Kind.PERMANENT,
                    "SNS rejected the message: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            // Everything else is treated as transient, including the config
            // failures - NotFoundException for a wrong topic ARN,
            // AuthorizationErrorException for bad credentials. Those will not
            // fix themselves, but they fail every event rather than one, so the
            // correct response is to hold the backlog and alert, not to discard.
            throw new EventPublishException(EventPublishException.Kind.TRANSIENT,
                    e.getMessage(), e);
        }
    }
}
