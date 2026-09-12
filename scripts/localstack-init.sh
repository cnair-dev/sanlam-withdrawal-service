#!/bin/bash
# Provisions the local messaging topology so `docker compose up` yields a
# working environment with no manual AWS CLI steps.
#
# An SQS queue is subscribed to the topic purely so the demo is VERIFIABLE:
# without a subscriber, a publish to SNS succeeds and disappears, proving
# nothing. With one, the delivered event can actually be read back.
set -e
export AWS_DEFAULT_REGION=af-south-1

TOPIC_ARN=$(awslocal sns create-topic --name withdrawal-events --output text --query 'TopicArn')
QUEUE_URL=$(awslocal sqs create-queue --queue-name withdrawal-events-demo --output text --query 'QueueUrl')
QUEUE_ARN=$(awslocal sqs get-queue-attributes --queue-url "$QUEUE_URL" \
              --attribute-names QueueArn --output text --query 'Attributes.QueueArn')

awslocal sns subscribe --topic-arn "$TOPIC_ARN" --protocol sqs \
         --notification-endpoint "$QUEUE_ARN" --attributes RawMessageDelivery=true

echo "SNS topic:      $TOPIC_ARN"
echo "SQS subscriber: $QUEUE_URL"
