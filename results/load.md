# Load test

Machine: Apple M6, 32 GB; openjdk version "21.0.13.0.3" 2026-10-24. Service, Postgres and load generator on the same machine.

## POST /orders at 500 requests/s, 5 runs of 60 s
Median across runs, with the range in brackets. Latency in ms, measured from when each request was due.

| p50 | p90 | p99 | p99.9 | max | errors |
|---|---|---|---|---|---|
| 0.40 (0.40 to 0.41) | 0.55 (0.53 to 0.56) | 0.84 (0.74 to 0.90) | 7.93 (1.83 to 55.93) | 31.41 (8.45 to 105.98) | 0 of 150000 |

## POST /orders at higher rates, one run each

| rate/s | achieved/s | p50 | p90 | p99 | p99.9 | max | errors |
|---|---|---|---|---|---|---|---|
| 1000 | 1000 | 0.40 | 0.54 | 0.75 | 2.02 | 7.85 | 0 of 30000 |
| 2000 | 2000 | 0.34 | 0.50 | 0.73 | 4.64 | 13.31 | 0 of 60000 |
| 4000 | 3999 | 0.27 | 0.41 | 0.74 | 6.63 | 10.93 | 0 of 120000 |
| 8000 | 7998 | 0.38 | 0.54 | 3.44 | 10.47 | 19.84 | 0 of 240000 |

## GET /orders/{id} at 500 requests/s

| p50 | p90 | p99 | p99.9 | max | errors |
|---|---|---|---|---|---|
| 0.16 | 0.20 | 0.38 | 1.26 | 9.21 | 0 of 30000 |

## Server-side time for POST /orders (Tomcat access log, every request including warm-ups)

791,000 requests: p50 0.28 ms, p99 3.38 ms, p99.9 42.14 ms, max 138.77 ms.
