package com.sanlam.banking.withdrawal;

import com.sanlam.banking.withdrawal.messaging.EventPublishException;
import com.sanlam.banking.withdrawal.messaging.EventPublisher;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Integration tests run against a REAL PostgreSQL via Testcontainers, not H2.
 *
 * That is not incidental. The correctness of this service rests on PostgreSQL's
 * behaviour when a conditional UPDATE meets a concurrently locked row - it
 * re-evaluates the WHERE clause against the newly committed row version. H2 does
 * not faithfully reproduce those row-locking semantics, so a test that passed on
 * H2 would prove nothing about the mechanism the design actually depends on.
 */
@Testcontainers
public abstract class AbstractPostgresIT {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("bank")
                    .withUsername("bank")
                    .withPassword("bank");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Relay and jobs are driven explicitly in tests, not by the scheduler.
        registry.add("app.outbox.poll-interval-ms", () -> "3600000");
        registry.add("app.reconciliation.initial-delay-ms", () -> "3600000");
    }

    /** Records published events instead of calling AWS. */
    public static class RecordingEventPublisher implements EventPublisher {
        public final List<String> published = new CopyOnWriteArrayList<>();
        private volatile EventPublishException.Kind failWith = null;

        @Override
        public void publish(String eventType, String payload, String subject) {
            EventPublishException.Kind kind = failWith;
            if (kind != null) {
                throw new EventPublishException(kind, kind == EventPublishException.Kind.PERMANENT
                        ? "simulated malformed message" : "simulated SNS outage", null);
            }
            published.add(payload);
        }

        public void setFailing(boolean failing) {
            this.failWith = failing ? EventPublishException.Kind.TRANSIENT : null;
        }

        public void failWith(EventPublishException.Kind kind) {
            this.failWith = kind;
        }
    }

    @TestConfiguration
    public static class TestPublisherConfig {
        @Bean
        @Primary
        public RecordingEventPublisher recordingEventPublisher() {
            return new RecordingEventPublisher();
        }
    }
}
