import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * Open-loop load generator: sends requests at a fixed rate whatever the server's speed, and
 * measures each latency from the moment the request was due, not when it was actually sent,
 * so a stalled server cannot hide its queueing delay (coordinated omission).
 *
 *   java loadtest/LoadTest.java MODE RATE SECONDS [WARMUP_SECONDS] [BASE_URL] [OUT_CSV]
 *
 * MODE is create (POST /orders, a fresh Idempotency-Key each time) or get (GET /orders/{id}
 * over 1,000 orders created first). Prints one summary line; OUT_CSV gets the same numbers.
 */
public class LoadTest {

    static final long SLOW_MS = Long.parseLong(System.getenv().getOrDefault("SLOW_MS", "0"));

    static final String[] SYMBOLS = {"AAPL", "MSFT", "NVDA", "AMZN", "GOOGL", "META", "JPM", "ICE", "CME", "GS"};

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        int rate = Integer.parseInt(args[1]);
        int seconds = Integer.parseInt(args[2]);
        int warmup = args.length > 3 ? Integer.parseInt(args[3]) : 10;
        String base = args.length > 4 ? args[4] : "http://localhost:8080";
        Path out = args.length > 5 ? Path.of(args[5]) : null;

        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).build();
        String[] ids = mode.equals("get") ? seed(http, base, 1000) : null;

        Result r = run(http, base, mode, ids, rate, warmup, seconds);

        String line = String.format(
                "mode=%s rate=%d/s seconds=%d sent=%d errors=%d achieved=%.0f/s p50=%.2fms p90=%.2fms p99=%.2fms p99.9=%.2fms max=%.2fms",
                mode, rate, seconds, r.lat.length, r.errors, r.lat.length / r.wallSeconds,
                pct(r.lat, 50), pct(r.lat, 90), pct(r.lat, 99), pct(r.lat, 99.9), r.lat[r.lat.length - 1] / 1e6);
        System.out.println(line + String.format(" sendDelay_p50=%.2fms sendDelay_p99=%.2fms", pct(r.late, 50), pct(r.late, 99)));
        if (out != null) {
            boolean header = !Files.exists(out);
            String row = String.format("%s,%d,%d,%d,%d,%.0f,%.3f,%.3f,%.3f,%.3f,%.3f%n", mode, rate, seconds,
                    r.lat.length, r.errors, r.lat.length / r.wallSeconds, pct(r.lat, 50), pct(r.lat, 90),
                    pct(r.lat, 99), pct(r.lat, 99.9), r.lat[r.lat.length - 1] / 1e6);
            Files.writeString(out, (header ? "mode,rate,seconds,sent,errors,achieved,p50_ms,p90_ms,p99_ms,p999_ms,max_ms\n" : "") + row,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        }
    }

    record Result(long[] lat, long[] late, int errors, double wallSeconds) {}

    /** One unbroken stream of requests; the first warmup seconds are sent but not recorded. */
    static Result run(HttpClient http, String base, String mode, String[] ids, int rate, int warmup, int seconds)
            throws Exception {
        int skip = rate * warmup;
        int n = rate * seconds;
        long interval = 1_000_000_000L / rate;
        long[] lat = new long[n];
        long[] late = new long[n];
        AtomicInteger errors = new AtomicInteger();
        long start = System.nanoTime() + 50_000_000L;
        CompletableFuture<?>[] pending = new CompletableFuture<?>[skip + n];
        long measuredStart = start + skip * interval;
        for (int i = 0; i < skip + n; i++) {
            long due = start + i * interval;
            // Sleeping overshoots by milliseconds on macOS, which would count as server
            // latency; sleep until 2 ms before the request is due, then spin.
            long wait = due - System.nanoTime();
            if (wait > 2_000_000L) {
                LockSupport.parkNanos(wait - 2_000_000L);
            }
            while (System.nanoTime() < due) {
                Thread.onSpinWait();
            }
            if (i < skip) {
                pending[i] = http.sendAsync(request(base, mode, ids), HttpResponse.BodyHandlers.discarding());
                continue;
            }
            int slot = i - skip;
            late[slot] = System.nanoTime() - due;
            pending[i] = http.sendAsync(request(base, mode, ids), HttpResponse.BodyHandlers.discarding())
                    .whenComplete((res, err) -> {
                        lat[slot] = System.nanoTime() - due;
                        if (err != null || res.statusCode() >= 300) {
                            errors.incrementAndGet();
                        }
                        if (SLOW_MS > 0 && lat[slot] > SLOW_MS * 1_000_000L) {
                            System.err.printf("slow due=%.1fms lat=%.1fms done_epoch_ms=%d%n", (due - measuredStart) / 1e6, lat[slot] / 1e6, System.currentTimeMillis());
                        }
                    });
        }
        CompletableFuture.allOf(pending).exceptionally(e -> null).join();
        double wall = (System.nanoTime() - measuredStart) / 1e9;
        Arrays.sort(lat);
        Arrays.sort(late);
        return new Result(lat, late, errors.get(), wall);
    }

    static HttpRequest request(String base, String mode, String[] ids) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        if (mode.equals("get")) {
            return HttpRequest.newBuilder(URI.create(base + "/orders/" + ids[rnd.nextInt(ids.length)])).GET().build();
        }
        String body = String.format(
                "{\"account\":\"acct-%d\",\"symbol\":\"%s\",\"side\":\"%s\",\"type\":\"LIMIT\",\"quantity\":%d,\"limitPrice\":\"%d.%02d\"}",
                rnd.nextInt(100), SYMBOLS[rnd.nextInt(SYMBOLS.length)], rnd.nextBoolean() ? "BUY" : "SELL",
                1 + rnd.nextInt(1000), 10 + rnd.nextInt(500), rnd.nextInt(100));
        return HttpRequest.newBuilder(URI.create(base + "/orders"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    static String[] seed(HttpClient http, String base, int n) throws Exception {
        String[] ids = new String[n];
        for (int i = 0; i < n; i++) {
            String b = http.send(request(base, "create", null), HttpResponse.BodyHandlers.ofString()).body();
            ids[i] = b.substring(b.indexOf("\"id\":\"") + 6, b.indexOf("\"id\":\"") + 42);
        }
        return ids;
    }

    static double pct(long[] sorted, double p) {
        int i = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(i, sorted.length - 1))] / 1e6;
    }
}
