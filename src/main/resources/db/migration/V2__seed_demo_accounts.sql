-- Demo data for the local runnable environment.
--
-- Opening balances are recorded AS LEDGER ENTRIES, not just as a balance value.
-- If an account's balance simply appeared with no corresponding ledger movement,
-- the reconciliation control could never prove the cached balance and the ledger
-- agree - money would exist with no recorded origin, which is precisely what a
-- double-entry system is supposed to make impossible.
--
-- Direction convention: DEBIT reduces the account's balance, CREDIT increases it
-- (the customer statement convention). Funding an account therefore CREDITs the
-- customer and DEBITs the system settlement account.

INSERT INTO accounts(id, balance, currency, status) VALUES
    (1001, 1000.00, 'ZAR', 'ACTIVE'),
    (1002,  250.00, 'ZAR', 'ACTIVE'),
    (1003,  750.00, 'ZAR', 'FROZEN');   -- demonstrates the status gate

INSERT INTO ledger_entry(transaction_id, account_id, direction, amount, currency, correlation_id) VALUES
    ('00000000-0000-0000-0000-000000001001', 1001, 'CREDIT', 1000.00, 'ZAR', 'opening-balance'),
    ('00000000-0000-0000-0000-000000001001', 9000, 'DEBIT',  1000.00, 'ZAR', 'opening-balance'),
    ('00000000-0000-0000-0000-000000001002', 1002, 'CREDIT',  250.00, 'ZAR', 'opening-balance'),
    ('00000000-0000-0000-0000-000000001002', 9000, 'DEBIT',   250.00, 'ZAR', 'opening-balance'),
    ('00000000-0000-0000-0000-000000001003', 1003, 'CREDIT',  750.00, 'ZAR', 'opening-balance'),
    ('00000000-0000-0000-0000-000000001003', 9000, 'DEBIT',   750.00, 'ZAR', 'opening-balance');
