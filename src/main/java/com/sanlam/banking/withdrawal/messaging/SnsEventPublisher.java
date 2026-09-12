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
     * A standard rather than FIFO topic: this is fan-out to independent consumers that each
     * tolerate duplicates, so the throughput and cost of standard delivery beat FIFO's
     * ordering and deduplication.
     *
     * <p>eventType is a message ATTRIBUTE as well as body content so subscribers can apply
     * filter policies instead of paying to receive and parse messages they do not want.
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
            // Rejected on its own merits - malformed, oversized, a bad attribute. No
            // number of retries changes that.
            throw new EventPublishException(EventPublishException.Kind.PERMANENT,
                    "SNS rejected the message: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            // Everything else transient, including config failures - NotFoundException for
            // a wrong topic ARN, AuthorizationErrorException for bad credentials. Those
            // will not self-heal, but they fail every event rather than one, so hold the
            // backlog and alert rather than discard.
            throw new EventPublishException(EventPublishException.Kind.TRANSIENT,
                    e.getMessage(), e);
        }
    }
}
