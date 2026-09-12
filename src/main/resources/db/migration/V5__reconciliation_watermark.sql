-- Watermark for the incremental reconciliation pass.
--
-- The control previously re-derived every account's position from the entire
-- ledger every sixty seconds: a full GROUP BY over ledger_entry, plus a
-- correlated per-account SUM over its whole history. The ledger is never purged
-- - it carries a seven-year retention obligation - so the cost of the control
-- grew without bound while the thing it was checking did not. A control whose
-- expense scales with the age of the system is one that eventually gets turned
-- off, which is the worst possible outcome for an audit control.
--
-- Single-row table: BOOLEAN primary key plus CHECK (id) admits exactly one row,
-- so the watermark cannot be accidentally duplicated into ambiguity.
CREATE TABLE reconciliation_watermark (
    id             BOOLEAN     PRIMARY KEY DEFAULT TRUE,
    last_ledger_id BIGINT      NOT NULL DEFAULT 0,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT reconciliation_watermark_single_row CHECK (id)
);

INSERT INTO reconciliation_watermark (id, last_ledger_id) VALUES (TRUE, 0);
