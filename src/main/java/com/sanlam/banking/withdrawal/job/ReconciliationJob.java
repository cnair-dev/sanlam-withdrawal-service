package com.sanlam.banking.withdrawal.job;

import jakarta.annotation.PostConstruct;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The control that makes the ledger worth having.
 *
 * <p>{@code accounts.balance} is a deliberate denormalisation - a cached position for cheap
 * reads and the atomic conditional debit - while {@code ledger_entry} is the system of
 * record. Two representations of one fact need a control proving they agree, or the ledger
 * is decoration.
 *
 * <ol>
 *   <li>Every individual transaction balances: sum(debits) = sum(credits) per
 *       transaction_id, per currency. Only checkable because the legs share that key; the
 *       global sum alone would not detect two unrelated errors cancelling out.</li>
 *   <li>Each customer account's cached balance equals its net ledger movement. Works only
 *       because opening balances are themselves ledger entries.</li>
 * </ol>
 *
 * <p>One pass, re-deriving everything, once a day. An incremental pass bounded by a
 * watermark is the obvious optimisation and it is deliberately not here: the watermark has
 * to be a <i>visibility</i> boundary, and the obvious candidate is not one. {@code
 * MAX(id)} over a BIGSERIAL reads the highest id currently visible, but ids are allocated
 * at insert and published at commit, so a transaction that started earlier can commit after
 * the watermark has already moved past its ids - and its entries are then never examined
 * again. That needs a timestamp with a safety lag, {@code pg_snapshot_xmin}, or a nullable
 * {@code reconciled_at} column with a partial index; the last is the only one with no
 * correctness parameter to tune.
 *
 * <p>Until the ledger is large enough for a full sweep to hurt, the incremental version is
 * a harder control that checks strictly less. System accounts are excluded from invariant
 * 2: they hold no cached balance.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ReconciliationJob {

    /** Enough to identify a problem in an alert without an unbounded log line. */
    private static final int MAX_IDS_LOGGED = 20;

    private final JdbcClient jdbc;
    private final MeterRegistry meterRegistry;

    // Gauges, not counters: these answer "how many breaches exist right now", which only a
    // full recomputation knows.
    private final AtomicLong unbalancedTransactions = new AtomicLong();
    private final AtomicLong driftingAccounts       = new AtomicLong();

    @PostConstruct
    void bindMetrics() {
        meterRegistry.gauge("ledger.unbalanced.transactions", unbalancedTransactions, AtomicLong::get);
        meterRegistry.gauge("ledger.drifting.accounts", driftingAccounts, AtomicLong::get);
    }

    @Scheduled(cron = "${app.reconciliation.cron:0 30 3 * * *}",
               zone = "${app.schedule.zone:Africa/Johannesburg}")
    @Transactional(readOnly = true)
    public void reconcile() {
        // Grouped by currency in both invariants. Amounts in different currencies are not
        // addable, so a sum across them is a number with no meaning - and the settlement
        // account is exactly where entries in more than one would collect.
        List<String> unbalanced = jdbc.sql("""
                SELECT transaction_id::text
                  FROM ledger_entry
                 GROUP BY transaction_id, currency
                HAVING SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END) <> 0
                """).query(String.class).list();

        long drift = jdbc.sql("""
                SELECT count(*)
                  FROM accounts a
                 WHERE a.is_system = FALSE
                   AND a.balance <> COALESCE((
                           SELECT SUM(CASE WHEN l.direction = 'CREDIT' THEN l.amount ELSE -l.amount END)
                             FROM ledger_entry l
                            WHERE l.account_id = a.id AND l.currency = a.currency), 0)
                """).query(Long.class).single();

        unbalancedTransactions.set(unbalanced.size());
        driftingAccounts.set(drift);

        if (!unbalanced.isEmpty()) {
            // Truncated: a systemic fault would otherwise put every offending id into one
            // log line, which is where logging becomes the second incident.
            log.error("RECONCILIATION BREACH: {} unbalanced ledger transaction(s), first {}: {}",
                    unbalanced.size(), Math.min(unbalanced.size(), MAX_IDS_LOGGED),
                    unbalanced.stream().limit(MAX_IDS_LOGGED).toList());
        }
        if (drift > 0) {
            log.error("RECONCILIATION BREACH: {} account(s) whose balance disagrees with the ledger",
                    drift);
        }
        log.debug("Reconciliation complete: unbalancedTransactions={} driftingAccounts={}",
                unbalanced.size(), drift);
    }
}
