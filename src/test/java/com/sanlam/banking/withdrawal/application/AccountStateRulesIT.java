package com.sanlam.banking.withdrawal.application;

import com.sanlam.banking.withdrawal.AbstractPostgresIT;
import com.sanlam.banking.withdrawal.domain.WithdrawalCommand;
import com.sanlam.banking.withdrawal.domain.exception.AccountNotActiveException;
import com.sanlam.banking.withdrawal.domain.exception.AccountNotFoundException;
import com.sanlam.banking.withdrawal.domain.exception.CurrencyMismatchException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Which accounts this endpoint will act on, and how it refuses the rest.
 */
@SpringBootTest
@Import(AbstractPostgresIT.TestPublisherConfig.class)
class AccountStateRulesIT extends AbstractPostgresIT {

    private static final AtomicLong NEXT_ACCOUNT = new AtomicLong(820_000L);
    private static final long SETTLEMENT_ID = 9000L;

    @Autowired WithdrawalService withdrawalService;
    @Autowired JdbcClient jdbc;

    private long seed(String status) {
        long id = NEXT_ACCOUNT.incrementAndGet();
        jdbc.sql("INSERT INTO accounts(id, balance, currency, status) VALUES (:id, 500.00, 'ZAR', :status)")
                .param("id", id).param("status", status).update();
        // Funded as a balanced pair, the way V2 does it. A balance with no
        // ledger origin is a reconciliation breach, and a fixture should not
        // leave one behind for whatever runs next.
        jdbc.sql("""
                INSERT INTO ledger_entry(transaction_id, account_id, direction, amount, currency, correlation_id)
                VALUES (:txn, :id,         'CREDIT', 500.00, 'ZAR', 'state-rules-opening'),
                       (:txn, :settlement, 'DEBIT',  500.00, 'ZAR', 'state-rules-opening')
                """)
                .param("txn", UUID.randomUUID()).param("id", id)
                .param("settlement", SETTLEMENT_ID).update();
        return id;
    }

    /** Same shape as seed(), in a currency this service does not settle in. */
    private long seedForeignCurrencyAccount() {
        long id = NEXT_ACCOUNT.incrementAndGet();
        jdbc.sql("INSERT INTO accounts(id, balance, currency, status) VALUES (:id, 500.00, 'USD', 'ACTIVE')")
                .param("id", id).update();
        jdbc.sql("""
                INSERT INTO ledger_entry(transaction_id, account_id, direction, amount, currency, correlation_id)
                VALUES (:txn, :id,         'CREDIT', 500.00, 'USD', 'state-rules-opening'),
                       (:txn, :settlement, 'DEBIT',  500.00, 'USD', 'state-rules-opening')
                """)
                .param("txn", UUID.randomUUID()).param("id", id)
                .param("settlement", SETTLEMENT_ID).update();
        return id;
    }

    private WithdrawalCommand withdraw(long accountId) {
        return new WithdrawalCommand(accountId, new BigDecimal("100.00"),
                "test-client", UUID.randomUUID().toString(), "corr-state");
    }

    @ParameterizedTest(name = "a {0} account is refused, and the reason says which")
    @ValueSource(strings = { "FROZEN", "DORMANT", "CLOSED" })
    void nonActiveAccountsAreRefusedWithTheirOwnStatus(String status) {
        long accountId = seed(status);

        assertThatThrownBy(() -> withdrawalService.withdraw(withdraw(accountId)))
                .isInstanceOf(AccountNotActiveException.class)
                .extracting(e -> ((AccountNotActiveException) e).getStatus())
                .isEqualTo(status);
    }

    @Test
    @DisplayName("The settlement account is not reachable through the customer API")
    void systemAccountsAreNotWithdrawable() {
        // It is ACTIVE, and before is_system was part of the predicate the only
        // thing preventing this was its balance happening to be zero.
        jdbc.sql("UPDATE accounts SET balance = 1000.00 WHERE id = :id")
                .param("id", SETTLEMENT_ID).update();

        assertThatThrownBy(() -> withdrawalService.withdraw(withdraw(SETTLEMENT_ID)))
                .isInstanceOf(AccountNotFoundException.class);

        // Refused before any money moved, not after.
        BigDecimal balance = jdbc.sql("SELECT balance FROM accounts WHERE id = :id")
                .param("id", SETTLEMENT_ID).query(BigDecimal.class).single();
        assertThat(balance).isEqualByComparingTo("1000.00");

        jdbc.sql("UPDATE accounts SET balance = 0.00 WHERE id = :id")
                .param("id", SETTLEMENT_ID).update();
    }

    @Test
    @DisplayName("An unknown account is refused without a debit")
    void unknownAccountIsRefused() {
        assertThatThrownBy(() -> withdrawalService.withdraw(withdraw(99_999_999L)))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    @DisplayName("An active customer account is debited")
    void activeAccountIsDebited() {
        long accountId = seed("ACTIVE");

        var response = withdrawalService.withdraw(withdraw(accountId));

        assertThat(response.resultingBalance()).isEqualByComparingTo("400.00");
    }

    @Test
    @DisplayName("An account in another currency is refused, and the balance does not move")
    void accountInAnotherCurrencyIsRefused() {
        long accountId = seedForeignCurrencyAccount();

        assertThatThrownBy(() -> withdrawalService.withdraw(withdraw(accountId)))
                .isInstanceOf(CurrencyMismatchException.class)
                .hasMessageContaining("USD")
                .hasMessageContaining("ZAR");

        // The guard is in the UPDATE predicate, not a check before it, so the debit never
        // happened rather than happening and being reported oddly.
        BigDecimal balance = jdbc.sql("SELECT balance FROM accounts WHERE id = :id")
                .param("id", accountId).query(BigDecimal.class).single();
        assertThat(balance).isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("The event quotes the ledger's own instant, not a second clock")
    void eventTimeMatchesTheLedgerEntry() {
        long accountId = seed("ACTIVE");

        var response = withdrawalService.withdraw(withdraw(accountId));

        Instant ledgerTime = jdbc.sql("""
                SELECT DISTINCT created_at FROM ledger_entry
                 WHERE account_id = :id AND correlation_id = 'corr-state'
                """).param("id", accountId).query(Instant.class).single();

        assertThat(response.processedAt())
                .as("the response and the ledger are one movement and must carry one time")
                .isEqualTo(ledgerTime);

        String payload = jdbc.sql("SELECT payload::text FROM outbox_event WHERE aggregate_id = :id")
                .param("id", accountId).query(String.class).single();
        assertThat(payload)
                .as("and so must the event that leaves the building")
                .contains(ledgerTime.toString().replace("Z", ""));
    }
}
