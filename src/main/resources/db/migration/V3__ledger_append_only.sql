-- ---------------------------------------------------------------------------
-- Make the ledger's immutability a control rather than a convention.
--
-- ledger_entry was documented as insert-only and nothing enforced it. For a
-- record carrying a seven-year statutory retention obligation (FICA), "we only
-- ever INSERT" is a statement about today's code, not a property of the data.
-- An ORM misconfiguration, a well-meant data fix, or a future write path would
-- all silently rewrite history, and a reconciliation control that runs against
-- mutable data proves nothing.
--
-- Scope of what this actually buys, stated honestly:
--   * It stops the application, a migration, and an operator at a psql prompt.
--   * Row-level triggers do NOT fire on TRUNCATE, hence the separate statement
--     trigger below.
--   * It does not stop the table owner or a superuser, who can DISABLE TRIGGER.
--     Closing that requires the application role to not own the table, plus
--     REVOKE UPDATE, DELETE ON ledger_entry, and ultimately shipping the audit
--     trail to storage this database cannot reach. Those are deployment
--     concerns; this is the part that belongs in the schema.
--
-- Corrections stay possible the way they do in any ledger: post a reversing
-- entry. You never edit history, you append the contra.
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION ledger_reject_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION
        'ledger_entry is append-only: % rejected. Post a reversing entry instead.',
        TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER ledger_entry_no_update
    BEFORE UPDATE ON ledger_entry
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_mutation();

CREATE TRIGGER ledger_entry_no_delete
    BEFORE DELETE ON ledger_entry
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_mutation();

CREATE TRIGGER ledger_entry_no_truncate
    BEFORE TRUNCATE ON ledger_entry
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_reject_mutation();
