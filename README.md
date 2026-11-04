# order-entry-service

A REST service for entering, amending, canceling and filling stock orders, written in Java 21 with Spring Boot 3 and Postgres. It shows how an order API can stay correct when clients retry and requests race: each create carries an idempotency key, each change locks the order's row, and every change is written to an audit log in the same transaction.

## Run it

You need JDK 21, Maven, and Postgres on localhost:5432 with a role `orders` (password `orders`) that owns two databases, `orders` and `orders_test`.

```sh
psql -U postgres -c "CREATE ROLE orders LOGIN PASSWORD 'orders'" \
     -c "CREATE DATABASE orders OWNER orders" -c "CREATE DATABASE orders_test OWNER orders"

mvn verify                                   # tests, then the coverage report in target/site/jacoco
mvn -DskipTests package
java -jar target/order-entry-service-0.1.0.jar

curl -i -X POST localhost:8080/orders -H 'Content-Type: application/json' -H 'Idempotency-Key: 7f1c' \
  -d '{"account":"acct-1","symbol":"AAPL","side":"BUY","type":"LIMIT","quantity":100,"limitPrice":"190.25"}'

loadtest/run.sh && python3 loadtest/summarize.py   # load test, written to results/load.md
```

`DB_URL`, `DB_USER`, `DB_PASSWORD` and `DB_POOL` override the connection settings, `TEST_DB_URL` points the tests at their database, and Flyway creates the tables when the service starts.

## API

| Request | Does | Responses |
|---|---|---|
| `POST /orders` with an `Idempotency-Key` header | Creates an order | 201 new; 200 with the response header `Idempotent-Replayed: true` (this service's own) when the key already created this order; 422 when the key was used for a different order; 400 when the key or body is invalid |
| `GET /orders/{id}` | Reads an order | 200, 404 |
| `GET /orders?account=&status=&limit=` | Lists an account's orders, newest first | 200, 400 |
| `PATCH /orders/{id}` with `quantity`, `limitPrice` or both | Amends an open order | 200, 404, 409 when the order is filled or canceled, 422 when a rule is broken |
| `POST /orders/{id}/cancel` | Cancels the unfilled rest | 200, 404, 409 |
| `POST /orders/{id}/fills` with `quantity` and `price` | Applies an execution report | 200, 404, 409 when the order is filled or canceled, 422 when the fill exceeds the open quantity or breaks the limit price |
| `GET /orders/{id}/events` | Returns the audit log | 200, 404 |

Errors are problem responses (RFC 9457, the JSON error format for HTTP APIs). For a body that fails validation, the problem response also lists every invalid field.

## How it works

An order is `NEW`, `PARTIALLY_FILLED`, `FILLED` or `CANCELED`, and the last two are final. The rules live in `domain/Order.java`, an immutable record whose methods return the changed order. A limit order fills only at its limit price or better, and a fill can never exceed the order's open quantity. An amend cannot lower the quantity to or below what has already filled. Every amend, fill or cancel raises the order's version by one.

Creating an order writes three rows in one transaction: the order, its `CREATED` event and the idempotency key. The key is scoped to the account and stored with a SHA-256 hash of the order's fields, so a retry with the same body is recognized and a reused key with a different body is refused. The same key sent from a different account creates a separate order. The key row is inserted with `ON CONFLICT DO NOTHING`. When two requests with one key arrive together, the second waits on the key's primary key until the first commits, finds the key taken, rolls back its own order and returns the first one. The replay returns the order as it is now, so a retried create after a cancel shows the order as canceled.

Amend, cancel and fill read the order with `SELECT ... FOR UPDATE`, which makes concurrent changes to one order wait in line. The update also checks the version it read. In addition, each audit event's sequence number is the version it produced, so if two writers ever both produced version 3, the second insert of event 3 would fail on the event table's primary key. All of this SQL is written by hand and run through Spring's `JdbcClient`, with no ORM.

## Tests

`mvn verify` runs 29 test methods, 46 cases once parameterized tests expand, against a real Postgres. They passed in three consecutive local runs and on GitHub Actions, where the CI workflow runs the same `mvn verify` against a Postgres 17 service container. JaCoCo measures 95.6% line coverage and 90.4% branch coverage. `OrderTest` covers the state machine and the order rules without a database. `OrderApiTest` drives the HTTP API end to end, including three race tests:

- 50 identical creates with one key, sent at once, must produce one order, one 201 and 49 replays.
- 30 fills of 5 shares on a 100-share order, sent at once, must apply exactly 20 and leave the order filled. The other 10 find a filled order and get 409.
- A cancel raced against ten fills must leave the filled quantity plus the canceled quantity equal to the order quantity, with one audit event per version.

To check that the race tests can fail, two bugs were introduced on purpose. Checking for the key before inserting it, without `ON CONFLICT`, failed the duplicate test in 3 of 3 runs: 20 to 30 of the 49 duplicates got errors. Removing `FOR UPDATE` failed the fill test in 3 of 3 runs, in a different way. The version check still kept the data correct (the filled quantity always matched the accepted fills), but it refused 24 or 25 of the 30 fills with 409 when 20 should have applied. The row lock turns those conflicts into a short wait.

## Load test

On one Apple M6 Mac mini (32 GB), with the service, Postgres and the load generator on the same machine, creating orders at 500 requests per second gave these latencies across five 60-second runs (`results/load.md`):

| p50 | p90 | p99 | p99.9 | max | errors |
|---|---|---|---|---|---|
| 0.40 ms | 0.55 ms | 0.84 ms | 7.93 ms | 31.41 ms | 0 of 150,000 |

Each latency is the median of the five runs, and the errors are the total over all five. The p50 and p99 barely moved between runs (p99 0.74 to 0.90 ms), but the tail did: p99.9 ranged from 1.83 to 55.93 ms and the maximum from 8.45 to 105.98 ms. The Tomcat access log shows the same tail inside the service, so those requests were slow on the server, although the generator competes for the same CPU and may add to it. One run each at higher rates kept every request successful up to 8,000 per second, where p99 rose to 3.44 ms. At 12,000 per second the run collapsed: 311,977 of the 360,000 requests sent (87%) failed, and responses of either kind came back at only 6,302 per second. That run does not show whether the service or the generator gave out first, since both share the machine, and runs at 16,000 and 24,000 per second crashed the generator. As a result, 8,000 per second is the highest rate measured without errors here, not a measured ceiling. Reading an order at 500 per second took 0.16 ms at p50 and 0.38 ms at p99.

`loadtest/LoadTest.java` sends requests at a fixed rate whatever the server's speed and measures each one from the moment it was due, so a stalled server cannot hide the requests queued behind it. Every create carries a fresh idempotency key. Because sleeping overshoots by milliseconds on macOS, the generator sleeps until 2 ms before each request is due and then busy-waits. To check the generator itself, `oha`, an open-source HTTP load tool, ran the same 500-per-second read test in a separate run and measured 0.23 ms at p50 and 0.8 ms at p99, against this generator's 0.16 ms and 0.38 ms. The two tools ran separately and differ by up to a factor of two, but both put reads well under a millisecond, which is what the check needed to show.

## Limits

Postgres ran with its default settings on macOS. There, the sync at each commit does not force the drive's write cache to disk (that needs `wal_sync_method = fsync_writethrough`), so commits are cheaper than on a Linux server whose drive honors flushes. The load figures are for one machine with the generator competing for the same CPU. The service has no authentication, no risk checks and no matching engine: fills arrive from outside, as execution reports from a trading venue would. Idempotency keys never expire.
