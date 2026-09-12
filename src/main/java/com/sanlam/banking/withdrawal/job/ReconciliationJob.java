package com.sanlam.banking.withdrawal.job;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The control that makes the ledger worth having.
 *
 * accounts.balance is a deliberate denormalisation: it is a cached position,
 * maintained for cheap reads and for the atomic conditional debit. ledger_entry
 * is the system of record. Two representations of the same fact need a control
 * proving they agree, otherwise the ledger is decoration - a table nobody checks.
 *
 * Two invariants are asserted:
 *   1. Every individual transaction balances: sum(debits) = sum(credits) per
 *      transaction_id. This is only checkable because the legs share a
 *      transaction_id; without that grouping key only the global sum could be
 *      verified, which would not detect two unrelated errors cancelling out.
 *   2. Each customer account's cached balance equals its opening balance plus
 *      net ledger movement.
 *
 * System accounts are excluded from (2) by design: they hold no cached balance.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ReconciliationJob {

    private final JdbcClient jdbc;
    private final MeterRegistry meterRegistry;

    private final AtomicLong unbalancedTransactions = new AtomicLong();
    private final AtomicLong driftingAccounts       = new AtomicLong();

    @jakarta.annotation.PostConstruct
    void bindMetrics() {
        meterRegistry.gauge("ledger.unbalanced.transactions", unbalancedTransactions, AtomicLong::get);
        meterRegistry.gauge("ledger.drifting.accounts", driftingAccounts, AtomicLong::get);
    }

    @Scheduled(fixedDelayString = "${app.reconciliation.interval-ms:60000}",
               initialDelayString = "${app.reconciliation.initial-delay-ms:15000}")
    public void reconcile() {
        List<String> unbalanced = jdbc.sql("""
                SELECT transaction_id::text
                  FROM ledger_entry
                 GROUP BY transaction_id
                HAVING SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END) <> 0
                """).query(String.class).list();

        unbalancedTransactions.set(unbalanced.size());
        if (!unbalanced.isEmpty()) {
            log.error("RECONCILIATION BREACH: {} unbalanced ledger transaction(s): {}",
                    unbalanced.size(), unbalanced);
        }

        // Cached balance must equal the account's net ledger movement. This only
        // works because opening balances are themselves ledger entries - a
        // balance with no ledger origin could never be reconciled.
        long drift = jdbc.sql("""
                SELECT count(*)
                  FROM accounts a
                 WHERE a.is_system = FALSE
                   AND a.balance <> COALESCE((
                           SELECT SUM(CASE WHEN l.direction = 'CREDIT' THEN l.amount ELSE -l.amount END)
                             FROM ledger_entry l
                            WHERE l.account_id = a.id), 0)
                """).query(Long.class).single();
        driftingAccounts.set(drift);

        log.debug("Reconciliation complete: unbalancedTransactions={} driftingAccounts={}",
                unbalanced.size(), drift);
    }
}
