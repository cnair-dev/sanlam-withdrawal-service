package com.sanlam.banking.withdrawal.persistence;

import com.sanlam.banking.withdrawal.AbstractPostgresIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The ledger is the system of record behind a seven-year retention obligation,
 * and the reconciliation control is only worth running if the data underneath it
 * cannot be rewritten. These tests are the difference between that being a
 * property of the schema and a property of today's code.
 */
@SpringBootTest
@Import(AbstractPostgresIT.TestPublisherConfig.class)
class LedgerAppendOnlyIT extends AbstractPostgresIT {

    private static final long SETTLEMENT_ID = 9000L;

    @Autowired JdbcClient jdbc;

    private UUID seedEntry(long accountId) {
        jdbc.sql("INSERT INTO accounts(id, balance, currency, status) VALUES (:id, 100.00, 'ZAR', 'ACTIVE')")
                .param("id", accountId).update();
        UUID txn = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO ledger_entry(transaction_id, account_id, direction, amount, currency, correlation_id)
                VALUES (:txn, :id,         'DEBIT',  50.00, 'ZAR', 'append-only-test'),
                       (:txn, :settlement, 'CREDIT', 50.00, 'ZAR', 'append-only-test')
                """)
                .param("txn", txn).param("id", accountId).param("settlement", SETTLEMENT_ID).update();
        return txn;
    }

    @Test
    @DisplayName("An INSERT is the only write the ledger accepts")
    void insertIsPermitted() {
        UUID txn = seedEntry(810_001L);

        Long rows = jdbc.sql("SELECT count(*) FROM ledger_entry WHERE transaction_id = :txn")
                .param("txn", txn).query(Long.class).single();

        assertThat(rows).isEqualTo(2L);
    }

    @Test
    @DisplayName("UPDATE on a posted entry is rejected by the database, not by convention")
    void updateIsRejected() {
        UUID txn = seedEntry(810_002L);

        assertThatThrownBy(() -> jdbc.sql("UPDATE ledger_entry SET amount = 1.00 WHERE transaction_id = :txn")
                        .param("txn", txn).update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("DELETE on a posted entry is rejected")
    void deleteIsRejected() {
        UUID txn = seedEntry(810_003L);

        assertThatThrownBy(() -> jdbc.sql("DELETE FROM ledger_entry WHERE transaction_id = :txn")
                        .param("txn", txn).update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("TRUNCATE is rejected too - row triggers alone would not have caught it")
    void truncateIsRejected() {
        assertThatThrownBy(() -> jdbc.sql("TRUNCATE ledger_entry CASCADE").update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("A correction is a reversing entry, and the pair nets to zero")
    void correctionIsPostedAsAReversal() {
        long accountId = 810_004L;
        UUID original = seedEntry(accountId);

        // The supported way to undo a movement: post the contra, do not edit history.
        UUID reversal = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO ledger_entry(transaction_id, account_id, direction, amount, currency, correlation_id)
                VALUES (:txn, :id,         'CREDIT', 50.00, 'ZAR', 'reversal-of-' || :original),
                       (:txn, :settlement, 'DEBIT',  50.00, 'ZAR', 'reversal-of-' || :original)
                """)
                .param("txn", reversal).param("id", accountId)
                .param("settlement", SETTLEMENT_ID).param("original", original.toString()).update();

        BigDecimal net = jdbc.sql("""
                SELECT COALESCE(SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE -amount END), 0)
                  FROM ledger_entry WHERE account_id = :id
                """).param("id", accountId).query(BigDecimal.class).single();

        assertThat(net).isEqualByComparingTo("0.00");

        // Both movements remain on the record. The history is added to, never rewritten.
        Long entries = jdbc.sql("SELECT count(*) FROM ledger_entry WHERE account_id = :id")
                .param("id", accountId).query(Long.class).single();
        assertThat(entries).isEqualTo(2L);
    }
}
