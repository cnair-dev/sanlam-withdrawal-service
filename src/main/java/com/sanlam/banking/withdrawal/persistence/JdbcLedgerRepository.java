package com.sanlam.banking.withdrawal.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class JdbcLedgerRepository implements LedgerRepository {

    private final JdbcClient jdbc;

    @Override
    public void recordWithdrawal(UUID transactionId, long accountId, long settlementAccountId,
                                 BigDecimal amount, String currency, String correlationId) {
        jdbc.sql("""
                INSERT INTO ledger_entry
                       (transaction_id, account_id, direction, amount, currency, correlation_id)
                VALUES (:txnId, :accountId,  'DEBIT',  :amount, :currency, :correlationId),
                       (:txnId, :settlement, 'CREDIT', :amount, :currency, :correlationId)
                """)
                .param("txnId", transactionId)
                .param("accountId", accountId)
                .param("settlement", settlementAccountId)
                .param("amount", amount)
                .param("currency", currency)
                .param("correlationId", correlationId)
                .update();
    }

    @Override
    public BigDecimal netMovementFor(long accountId) {
        return jdbc.sql("""
                SELECT COALESCE(SUM(CASE WHEN direction = 'DEBIT' THEN -amount ELSE amount END), 0)
                  FROM ledger_entry
                 WHERE account_id = :accountId
                """)
                .param("accountId", accountId)
                .query(BigDecimal.class)
                .single();
    }
}
