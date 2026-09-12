package com.sanlam.banking.withdrawal.job;

import jakarta.annotation.PostConstruct;
import io.micrometer.core.instrument.Counter;
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
 * <p>{@code accounts.balance} is a deliberate denormalisation: a cached position,
 * maintained for cheap reads and for the atomic conditional debit.
 * {@code ledger_entry} is the system of record. Two representations of the same
 * fact need a control proving they agree, otherwise the ledger is decoration - a
 * table nobody checks.
 *
 * <p>Two invariants are asserted:
 * <ol>
 *   <li>Every individual transaction balances: sum(debits) = sum(credits) per
 *       transaction_id. Only checkable because the legs share a transaction_id;
 *       without that grouping key only the global sum could be verified, which
 *       would not detect two unrelated errors cancelling out.</li>
 *   <li>Each customer account's cached balance equals its net ledger movement.
 *       This works only because opening balances are themselves ledger entries -
 *       a balance with no ledger origin could never be reconciled.</li>
 * </ol>
 *
 * <p>It runs in two passes, and both are needed.
 *
 * <p>The <b>incremental</b> pass runs on a short interval and looks only at
 * entries appended since the last watermark. Its cost is proportional to
 * throughput rather than to the age of the ledger, which matters because the
 * ledger is never purged: the previous version re-derived every account's
 * position from the entire history every sixty seconds, so the control got more
 * expensive every day it ran while the thing it checked did not.
 *
 * <p>The <b>full sweep</b> runs daily and re-derives everything. It is not
 * redundant: the incremental pass can only examine accounts that appear in new
 * ledger entries, so it is structurally blind to a balance changed with no
 * ledger entry behind it at all - a direct UPDATE, a restore, a bad migration.
 * That is precisely the failure this control exists to catch, and only a full
 * recomputation finds it.
 *
 * <p>System accounts are excluded from invariant 2 by design: they hold no
 * cached balance, and their position is derived from the ledger on demand.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ReconciliationJob {

    /** Enough to identify a problem in an alert without an unbounded log line. */
    private static final int MAX_IDS_LOGGED = 20;

    private final JdbcClient jdbc;
    private final MeterRegistry meterRegistry;

    // Gauges are set by the full sweep only. A gauge answers "how many breaches
    // exist right now", and only a full recomputation knows that - if the
    // incremental pass also wrote them, a quiet minute would leave a stale
    // number standing and a busy one would overwrite the authoritative figure
    // with a partial view.
    private final AtomicLong unbalancedTransactions = new AtomicLong();
    private final AtomicLong driftingAccounts       = new AtomicLong();

    // The incremental pass increments a counter instead. "A breach was detected"
    // is an event, and a counter is the instrument that survives one: it can be
    // alerted on with increase() over a window even if the next sweep shows the
    // problem resolved.
    private Counter breachesDetected;

    @PostConstruct
    void bindMetrics() {
        meterRegistry.gauge("ledger.unbalanced.transactions", unbalancedTransactions, AtomicLong::get);
        meterRegistry.gauge("ledger.drifting.accounts", driftingAccounts, AtomicLong::get);
        breachesDetected = Counter.builder("ledger.reconciliation.breaches")
                .description("Reconciliation breaches detected since startup")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${app.reconciliation.interval-ms:60000}",
               initialDelayString = "${app.reconciliation.initial-delay-ms:15000}")
    @Transactional
    public void reconcileIncrementally() {
        long from = jdbc.sql("SELECT last_ledger_id FROM reconciliation_watermark")
                .query(Long.class).single();
        long to = jdbc.sql("SELECT COALESCE(MAX(id), 0) FROM ledger_entry")
                .query(Long.class).single();

        if (to <= from) {
            return;
        }

        // Both queries widen from the new entries to the whole transaction or
        // account they belong to, because a transaction's legs can straddle the
        // window boundary and a balance reflects an account's entire history.
        List<String> unbalanced = jdbc.sql("""
                SELECT transaction_id::text
                  FROM ledger_entry
                 WHERE transaction_id IN (
                           SELECT DISTINCT transaction_id FROM ledger_entry
                            WHERE id > :from AND id <= :to)
                 GROUP BY transaction_id
                HAVING SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END) <> 0
                """).param("from", from).param("to", to).query(String.class).list();

        long drift = jdbc.sql("""
                SELECT count(*)
                  FROM accounts a
                 WHERE a.is_system = FALSE
                   AND a.id IN (SELECT DISTINCT account_id FROM ledger_entry
                                 WHERE id > :from AND id <= :to)
                   AND a.balance <> COALESCE((
                           SELECT SUM(CASE WHEN l.direction = 'CREDIT' THEN l.amount ELSE -l.amount END)
                             FROM ledger_entry l
                            WHERE l.account_id = a.id), 0)
                """).param("from", from).param("to", to).query(Long.class).single();

        breachesDetected.increment(unbalanced.size() + drift);
        log("incremental", unbalanced, drift);

        jdbc.sql("UPDATE reconciliation_watermark SET last_ledger_id = :to, updated_at = now()")
                .param("to", to).update();
    }

    @Scheduled(cron = "${app.reconciliation.full-sweep-cron:0 30 3 * * *}")
    @Transactional(readOnly = true)
    public void reconcileEverything() {
        List<String> unbalanced = jdbc.sql("""
                SELECT transaction_id::text
                  FROM ledger_entry
                 GROUP BY transaction_id
                HAVING SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END) <> 0
                """).query(String.class).list();

        long drift = jdbc.sql("""
                SELECT count(*)
                  FROM accounts a
                 WHERE a.is_system = FALSE
                   AND a.balance <> COALESCE((
                           SELECT SUM(CASE WHEN l.direction = 'CREDIT' THEN l.amount ELSE -l.amount END)
                             FROM ledger_entry l
                            WHERE l.account_id = a.id), 0)
                """).query(Long.class).single();

        unbalancedTransactions.set(unbalanced.size());
        driftingAccounts.set(drift);
        log("full sweep", unbalanced, drift);
    }

    private void log(String pass, List<String> unbalanced, long drift) {
        if (!unbalanced.isEmpty()) {
            // Truncated deliberately: a systemic fault would otherwise put every
            // offending id into a single log line, which is the point at which
            // logging becomes the second incident.
            log.error("RECONCILIATION BREACH ({}): {} unbalanced ledger transaction(s), first {}: {}",
                    pass, unbalanced.size(), Math.min(unbalanced.size(), MAX_IDS_LOGGED),
                    unbalanced.stream().limit(MAX_IDS_LOGGED).toList());
        }
        if (drift > 0) {
            log.error("RECONCILIATION BREACH ({}): {} account(s) whose balance disagrees with the ledger",
                    pass, drift);
        }
        log.debug("Reconciliation {} complete: unbalancedTransactions={} driftingAccounts={}",
                pass, unbalanced.size(), drift);
    }
}
