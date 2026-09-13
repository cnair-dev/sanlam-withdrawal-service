#!/usr/bin/env bash
# Reproducible proof of the DB claims behind the withdrawal design.
# Run: bash verify_db_claims.sh     (needs Docker; uses port 55432)
set -u
C=sanlam-verify
docker rm -f $C >/dev/null 2>&1
docker run -d --name $C -e POSTGRES_PASSWORD=verify -e POSTGRES_DB=bank -p 55432:5432 postgres:16-alpine >/dev/null
until docker exec $C pg_isready -U postgres -d bank >/dev/null 2>&1; do sleep 1; done
P="docker exec -i $C psql -U postgres -d bank -v ON_ERROR_STOP=0"
$P -q -c "CREATE TABLE accounts(id BIGINT PRIMARY KEY, balance NUMERIC(19,2) NOT NULL, status TEXT NOT NULL DEFAULT 'ACTIVE');
          CREATE TABLE outbox_event(id BIGSERIAL PRIMARY KEY, published_at TIMESTAMPTZ NULL);
          CREATE TABLE idempotency_key(idempotency_key TEXT PRIMARY KEY, response_body TEXT);
          INSERT INTO accounts VALUES (1,100.00,'ACTIVE');
          INSERT INTO outbox_event(published_at) SELECT NULL FROM generate_series(1,6);"

banner(){ echo; echo "=============== $1 ==============="; }

banner "E1  ORIGINAL BUG: check-then-act overdraws"
$P -q -c "UPDATE accounts SET balance=100.00 WHERE id=1;"
( $P -q <<'X'
BEGIN; SELECT balance FROM accounts WHERE id=1; SELECT pg_sleep(2);
UPDATE accounts SET balance=balance-60 WHERE id=1; COMMIT;
X
) >/dev/null 2>&1 &
sleep 0.5
( $P -q <<'X'
BEGIN; SELECT balance FROM accounts WHERE id=1; SELECT pg_sleep(2);
UPDATE accounts SET balance=balance-60 WHERE id=1; COMMIT;
X
) >/dev/null 2>&1 &
wait
echo "two concurrent withdrawals of 60 from 100 ->"; $P -q -c "SELECT balance FROM accounts WHERE id=1;"

banner "E2  FIX: atomic conditional UPDATE @ READ COMMITTED"
$P -q -c "UPDATE accounts SET balance=100.00 WHERE id=1;"
( $P <<'X'
BEGIN; UPDATE accounts SET balance=balance-60 WHERE id=1 AND balance>=60; SELECT pg_sleep(2); COMMIT;
X
) >a.log 2>&1 &
sleep 0.5
( $P <<'X'
BEGIN; UPDATE accounts SET balance=balance-60 WHERE id=1 AND balance>=60; COMMIT;
X
) >b.log 2>&1 &
wait
echo "A: $(grep -E '^UPDATE [0-9]' a.log)   B: $(grep -E '^UPDATE [0-9]' b.log)  <- 0 rows = InsufficientFunds"
$P -q -c "SELECT balance FROM accounts WHERE id=1;"

banner "E3  SAME @ REPEATABLE READ -> serialization failure (why RC is REQUIRED, not just OK)"
$P -q -c "UPDATE accounts SET balance=100.00 WHERE id=1;"
( $P <<'X'
BEGIN ISOLATION LEVEL REPEATABLE READ; UPDATE accounts SET balance=balance-60 WHERE id=1 AND balance>=60; SELECT pg_sleep(2); COMMIT;
X
) >a.log 2>&1 &
sleep 0.5
( $P <<'X'
BEGIN ISOLATION LEVEL REPEATABLE READ; UPDATE accounts SET balance=balance-60 WHERE id=1 AND balance>=60; COMMIT;
X
) >b.log 2>&1 &
wait
echo "A: $(grep -E '^UPDATE [0-9]' a.log)"; grep ERROR b.log | sed 's/^/B: /'

banner "E4  MONEY SCALE: numeric(19,2) silently rounds on assignment"
for amt in 0.004 0.005 0.006; do
  $P -q -c "UPDATE accounts SET balance=100.00 WHERE id=1;"
  $P -q -c "UPDATE accounts SET balance=balance-$amt WHERE id=1 AND balance>=$amt;"
  echo "  withdraw $amt -> reported success, balance now $($P -A -t -c 'SELECT balance FROM accounts WHERE id=1;')"
done
echo "  ^ anything up to and including 0.005 leaves the balance untouched:"
echo "    99.995 rounds half-away-from-zero back to 100.00."
echo
echo "  and the CHECK that looks like it guards this cannot fire, because the"
echo "  column coerces the value before the constraint is evaluated:"
$P -q -c "DROP TABLE IF EXISTS scale_demo; CREATE TABLE scale_demo(bal NUMERIC(19,2) CHECK (scale(bal) <= 2));"
echo "  INSERT 55.1234 into NUMERIC(19,2) CHECK (scale(bal) <= 2):"
$P -c "INSERT INTO scale_demo VALUES (55.1234);" | sed 's/^/    /'
$P -A -t -c "SELECT '    stored as '||bal||' at scale '||scale(bal)||' - the constraint never fires' FROM scale_demo;"

banner "E5  IDEMPOTENCY: the production statement - TTL re-claim, and who blocks"
$P -q -c "DROP TABLE IF EXISTS idempotency_key;
          CREATE TABLE idempotency_key(client_id TEXT, idempotency_key TEXT, request_hash TEXT,
                 response_body TEXT, expires_at TIMESTAMPTZ NOT NULL,
                 PRIMARY KEY(client_id, idempotency_key));"

# The statement the service actually runs. DO NOTHING cannot express the TTL re-claim,
# which is the reason for DO UPDATE - NOT, as an earlier version of this script and the
# repository comment both claimed, that DO NOTHING fails to block. Measured below.
CLAIM="INSERT INTO idempotency_key(client_id, idempotency_key, request_hash, expires_at)
       VALUES ('c','k','hash-%s', now() + make_interval(hours => %s))
       ON CONFLICT (client_id, idempotency_key) DO UPDATE
          SET request_hash = EXCLUDED.request_hash, expires_at = EXCLUDED.expires_at,
              response_body = NULL
        WHERE idempotency_key.expires_at < now()"

echo "-- a live key is NOT re-claimed, and the winner's stored response survives:"
$P -q -c "$(printf "$CLAIM" A 1);"
$P -q -c "UPDATE idempotency_key SET response_body='winner-response';"
$P -c "$(printf "$CLAIM" B 1);"
$P -q -c "SELECT request_hash, response_body FROM idempotency_key;"

echo "-- an EXPIRED key IS re-claimed, and the stale response is cleared:"
$P -q -c "UPDATE idempotency_key SET expires_at = now() - interval '1 second';"
$P -c "$(printf "$CLAIM" C 1);"
$P -q -c "SELECT request_hash, response_body FROM idempotency_key;"

echo "-- under concurrency BOTH forms block on the speculative-insertion token."
echo "   The loser waits for the winner to commit, then sees a live key. DO UPDATE is"
echo "   not what makes it block - that was a wrong claim, measured here:"
for MODE in "DO NOTHING" "DO UPDATE SET request_hash=EXCLUDED.request_hash WHERE idempotency_key.expires_at < now()"; do
  $P -q -c "DELETE FROM idempotency_key;"
  ( $P -q <<X
BEGIN; INSERT INTO idempotency_key(client_id,idempotency_key,request_hash,expires_at)
 VALUES('c','k','A', now()+interval '1 hour') ON CONFLICT (client_id,idempotency_key) $MODE;
SELECT pg_sleep(2.5); COMMIT;
X
  ) >/dev/null 2>&1 &
  sleep 0.4
  START=$(date +%s)
  ( $P <<X
BEGIN; INSERT INTO idempotency_key(client_id,idempotency_key,request_hash,expires_at)
 VALUES('c','k','B', now()+interval '1 hour') ON CONFLICT (client_id,idempotency_key) $MODE; COMMIT;
X
  ) >b.log 2>&1
  END=$(date +%s)
  wait
  echo "   $(printf '%-22s' "${MODE%% *} ${MODE#* }" | cut -c1-22) waited ~$((END-START))s, errors: $(grep -c ERROR b.log), winner kept: $($P -A -t -c 'SELECT request_hash FROM idempotency_key')"
done

banner "E6  OUTBOX: what SKIP LOCKED actually buys"
# The relay marks rows PUBLISHED in the same transaction that claims them, so
# worker A below does the same. Three arms, because the obvious two-arm version
# of this experiment proves the wrong thing.
Q="docker exec -i $C psql -U postgres -d bank -A -t"
arm(){
  $P -q -c "DROP TABLE IF EXISTS ob; CREATE TABLE ob(id BIGSERIAL PRIMARY KEY, status TEXT NOT NULL DEFAULT 'PENDING');
            INSERT INTO ob(status) SELECT 'PENDING' FROM generate_series(1,6);" >/dev/null 2>&1
  ( $Q <<X
BEGIN;
CREATE TEMP TABLE claimed AS SELECT id FROM ob WHERE status='PENDING' ORDER BY id $1 LIMIT 3;
SELECT 'A:'||string_agg(id::text,',' ORDER BY id) FROM claimed;
UPDATE ob SET status='PUBLISHED' WHERE id IN (SELECT id FROM claimed);
SELECT pg_sleep(3);
COMMIT;
X
  ) >a.log 2>&1 &
  sleep 0.6
  start=$(python3 -c 'import time;print(time.time())')
  ( $Q <<X
BEGIN;
SELECT 'B:'||string_agg(id::text,',' ORDER BY id)
  FROM (SELECT id FROM ob WHERE status='PENDING' ORDER BY id $1 LIMIT 3) s;
COMMIT;
X
  ) >b.log 2>&1
  waited=$(python3 -c "import time;print(f'{time.time()-$start:.2f}')")
  printf "  %-26s %-8s %-8s  B waited %ss\n" "$2" "$(grep '^A:' a.log)" "$(grep '^B:' b.log)" "$waited"
  wait
}
echo "  A claims 3 rows, marks them published, holds the transaction 3s. B starts 0.6s later."
echo
arm ""                        "no locking clause"
arm "FOR UPDATE"              "plain FOR UPDATE"
arm "FOR UPDATE SKIP LOCKED"  "FOR UPDATE SKIP LOCKED"
echo
echo "  Correctness comes from holding ANY row lock while the status update runs in"
echo "  the same transaction - plain FOR UPDATE is already safe, it just serialises"
echo "  the workers. SKIP LOCKED is a liveness property, not a correctness one."
echo

banner "E7  lock_timeout bounds the wait on a contended row"
$P -q -c "UPDATE accounts SET balance=100.00 WHERE id=1;"
( $P -q <<'X'
BEGIN; UPDATE accounts SET balance=balance-10 WHERE id=1 AND balance>=10; SELECT pg_sleep(4); COMMIT;
X
) >/dev/null 2>&1 &
sleep 0.5
( $P <<'X'
BEGIN; SET LOCAL lock_timeout='1s'; UPDATE accounts SET balance=balance-10 WHERE id=1 AND balance>=10; COMMIT;
X
) >b.log 2>&1 &
wait
grep -E 'ERROR|UPDATE [0-9]' b.log | sed 's/^/  /'

echo; echo "cleanup: docker rm -f $C"
