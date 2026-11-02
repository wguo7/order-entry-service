package io.github.wguo7.orders;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;

/** End-to-end over HTTP against a real Postgres (TEST_DB_URL, default localhost:5432/orders_test). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=${TEST_DB_URL:jdbc:postgresql://localhost:5432/orders_test}")
class OrderApiTest {

    record Res(int status, JsonNode body, HttpResponse<String> raw) {
        String id() {
            return body.get("id").asText();
        }
    }

    static final String LIMIT_BUY = """
            {"account":"acct-1","symbol":"AAPL","side":"BUY","type":"LIMIT","quantity":100,"limitPrice":"190.25"}""";

    @LocalServerPort
    int port;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    ObjectMapper json;

    final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void clean() {
        jdbc.sql("TRUNCATE idempotency_keys, order_events, orders").update();
    }

    Res call(String method, String path, String body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json");
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Res(r.statusCode(), r.body().isEmpty() ? null : json.readTree(r.body()), r);
    }

    Res create(String body, String key) throws Exception {
        return call("POST", "/orders", body, "Idempotency-Key", key);
    }

    long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    @Test
    void createReturns201AndWritesCreatedEvent() throws Exception {
        Res r = create(LIMIT_BUY, "k1");
        assertThat(r.status).isEqualTo(201);
        assertThat(r.raw.headers().firstValue("Location")).hasValue("/orders/" + r.id());
        assertThat(r.body.get("status").asText()).isEqualTo("NEW");
        assertThat(r.body.get("remainingQuantity").asLong()).isEqualTo(100);
        assertThat(r.body.get("limitPrice").decimalValue()).isEqualByComparingTo("190.25");

        Res events = call("GET", "/orders/" + r.id() + "/events", null);
        assertThat(events.body).hasSize(1);
        assertThat(events.body.get(0).get("type").asText()).isEqualTo("CREATED");
        assertThat(events.body.get(0).get("detail").get("quantity").asLong()).isEqualTo(100);
    }

    @Test
    void sameKeyAndBodyReplaysTheOrder() throws Exception {
        Res first = create(LIMIT_BUY, "k1");
        Res again = create(LIMIT_BUY.replace("190.25", "190.2500"), "k1");
        assertThat(again.status).isEqualTo(200);
        assertThat(again.raw.headers().firstValue("Idempotent-Replayed")).hasValue("true");
        assertThat(again.id()).isEqualTo(first.id());
        assertThat(count("orders")).isEqualTo(1);
        assertThat(count("order_events")).isEqualTo(1);
    }

    @Test
    void replayReturnsTheOrdersCurrentState() throws Exception {
        Res first = create(LIMIT_BUY, "k1");
        call("POST", "/orders/" + first.id() + "/cancel", null);
        Res again = create(LIMIT_BUY, "k1");
        assertThat(again.status).isEqualTo(200);
        assertThat(again.body.get("status").asText()).isEqualTo("CANCELED");
    }

    @Test
    void sameKeyDifferentBodyIs422() throws Exception {
        create(LIMIT_BUY, "k1");
        Res r = create(LIMIT_BUY.replace("100", "101"), "k1");
        assertThat(r.status).isEqualTo(422);
        assertThat(r.body.get("detail").asText()).contains("different request");
        assertThat(count("orders")).isEqualTo(1);
    }

    @Test
    void keysAreScopedToTheAccount() throws Exception {
        Res a = create(LIMIT_BUY, "k1");
        Res b = create(LIMIT_BUY.replace("acct-1", "acct-2"), "k1");
        assertThat(b.status).isEqualTo(201);
        assertThat(b.id()).isNotEqualTo(a.id());
    }

    @Test
    void missingOrOversizedKeyIs400() throws Exception {
        assertThat(call("POST", "/orders", LIMIT_BUY).status).isEqualTo(400);
        assertThat(create(LIMIT_BUY, "x".repeat(129)).status).isEqualTo(400);
        assertThat(count("orders")).isZero();
    }

    @Test
    void invalidBodiesAre400WithFieldErrors() throws Exception {
        Map<String, String> cases = Map.of(
                LIMIT_BUY.replace("100", "-5"), "quantity",
                LIMIT_BUY.replace("AAPL", "aapl"), "symbol",
                LIMIT_BUY.replace(",\"limitPrice\":\"190.25\"", ""), "priceMatchesType",
                LIMIT_BUY.replace("LIMIT", "MARKET"), "priceMatchesType",
                LIMIT_BUY.replace("190.25", "190.12345"), "limitPrice",
                LIMIT_BUY.replace("acct-1", "bad|acct"), "account");
        for (var c : cases.entrySet()) {
            Res r = create(c.getKey(), "k-" + c.getValue());
            assertThat(r.status).as(c.getKey()).isEqualTo(400);
            assertThat(r.body.get("errors").toString()).as(c.getKey()).contains(c.getValue());
        }
        assertThat(create(LIMIT_BUY.replace("\"BUY\"", "\"HOLD\""), "k-side").status).isEqualTo(400);
        assertThat(create("{not json", "k-json").status).isEqualTo(400);
        assertThat(count("orders")).isZero();
    }

    @Test
    void unknownOrderIs404() throws Exception {
        String path = "/orders/00000000-0000-0000-0000-000000000000";
        assertThat(call("GET", path, null).status).isEqualTo(404);
        assertThat(call("POST", path + "/cancel", null).status).isEqualTo(404);
        assertThat(call("GET", path + "/events", null).status).isEqualTo(404);
        assertThat(call("PATCH", path, "{\"quantity\":5}").status).isEqualTo(404);
    }

    @Test
    void amendWritesFromAndTo() throws Exception {
        String id = create(LIMIT_BUY, "k1").id();
        Res r = call("PATCH", "/orders/" + id, "{\"quantity\":150,\"limitPrice\":\"190.50\"}");
        assertThat(r.status).isEqualTo(200);
        assertThat(r.body.get("quantity").asLong()).isEqualTo(150);
        assertThat(r.body.get("version").asInt()).isEqualTo(2);
        JsonNode amended = call("GET", "/orders/" + id + "/events", null).body.get(1);
        assertThat(amended.get("type").asText()).isEqualTo("AMENDED");
        assertThat(amended.get("detail").get("quantity").get("from").asLong()).isEqualTo(100);
        assertThat(amended.get("detail").get("limitPrice").get("to").decimalValue()).isEqualByComparingTo("190.50");
    }

    @Test
    void amendRulesMapTo422And409() throws Exception {
        String id = create(LIMIT_BUY, "k1").id();
        call("POST", "/orders/" + id + "/fills", "{\"quantity\":60,\"price\":\"190.00\"}");
        assertThat(call("PATCH", "/orders/" + id, "{\"quantity\":60}").status).isEqualTo(422);
        assertThat(call("PATCH", "/orders/" + id, "{}").status).isEqualTo(422);
        assertThat(call("PATCH", "/orders/" + id, "{\"quantity\":0}").status).isEqualTo(400);
        call("POST", "/orders/" + id + "/cancel", null);
        assertThat(call("PATCH", "/orders/" + id, "{\"quantity\":500}").status).isEqualTo(409);
    }

    @Test
    void cancelTwiceIs409() throws Exception {
        String id = create(LIMIT_BUY, "k1").id();
        Res r = call("POST", "/orders/" + id + "/cancel", null);
        assertThat(r.status).isEqualTo(200);
        assertThat(r.body.get("status").asText()).isEqualTo("CANCELED");
        assertThat(call("POST", "/orders/" + id + "/cancel", null).status).isEqualTo(409);
    }

    @Test
    void fillsMoveTheStatusAndRespectTheLimit() throws Exception {
        String id = create(LIMIT_BUY, "k1").id();
        String fills = "/orders/" + id + "/fills";
        assertThat(call("POST", fills, "{\"quantity\":10,\"price\":\"190.26\"}").status).isEqualTo(422);
        assertThat(call("POST", fills, "{\"quantity\":40,\"price\":\"190.25\"}").body.get("status").asText())
                .isEqualTo("PARTIALLY_FILLED");
        assertThat(call("POST", fills, "{\"quantity\":61,\"price\":\"190.00\"}").status).isEqualTo(422);
        assertThat(call("POST", fills, "{\"quantity\":60,\"price\":\"190.00\"}").body.get("status").asText())
                .isEqualTo("FILLED");
        assertThat(call("POST", fills, "{\"quantity\":1,\"price\":\"190.00\"}").status).isEqualTo(409);
        assertThat(call("POST", "/orders/" + id + "/cancel", null).status).isEqualTo(409);
    }

    @Test
    void auditTrailIsOrderedAndComplete() throws Exception {
        String id = create(LIMIT_BUY, "k1").id();
        call("PATCH", "/orders/" + id, "{\"quantity\":120}");
        call("POST", "/orders/" + id + "/fills", "{\"quantity\":20,\"price\":\"190.00\"}");
        call("POST", "/orders/" + id + "/cancel", null);
        JsonNode ev = call("GET", "/orders/" + id + "/events", null).body;
        List<String> types = new ArrayList<>();
        for (int i = 0; i < ev.size(); i++) {
            assertThat(ev.get(i).get("seq").asInt()).isEqualTo(i + 1);
            types.add(ev.get(i).get("type").asText());
        }
        assertThat(types).containsExactly("CREATED", "AMENDED", "PARTIALLY_FILLED", "CANCELED");
        assertThat(ev.get(3).get("detail").get("canceledQuantity").asLong()).isEqualTo(100);
        assertThat(call("GET", "/orders/" + id, null).body.get("version").asInt()).isEqualTo(4);
    }

    @Test
    void listFiltersByAccountAndStatus() throws Exception {
        String a = create(LIMIT_BUY, "k1").id();
        create(LIMIT_BUY, "k2");
        create(LIMIT_BUY.replace("acct-1", "acct-2"), "k3");
        call("POST", "/orders/" + a + "/cancel", null);
        assertThat(call("GET", "/orders?account=acct-1", null).body).hasSize(2);
        JsonNode canceled = call("GET", "/orders?account=acct-1&status=CANCELED", null).body;
        assertThat(canceled).hasSize(1);
        assertThat(canceled.get(0).get("id").asText()).isEqualTo(a);
        assertThat(call("GET", "/orders?account=acct-1&limit=0", null).status).isEqualTo(400);
        assertThat(call("GET", "/orders?account=acct-1&status=OPEN", null).status).isEqualTo(400);
    }

    /** 50 clients send the same create at once: one order, one 201, 49 replays of it. */
    @Test
    void concurrentDuplicatesCreateOneOrder() throws Exception {
        List<Res> results = race(50, () -> create(LIMIT_BUY, "same-key"));
        assertThat(results).extracting(Res::id).containsOnly(results.get(0).id());
        assertThat(results).filteredOn(r -> r.status == 201).hasSize(1);
        assertThat(results).filteredOn(r -> r.status == 200).hasSize(49);
        assertThat(count("orders")).isEqualTo(1);
        assertThat(count("idempotency_keys")).isEqualTo(1);
        assertThat(count("order_events")).isEqualTo(1);
    }

    /** 30 fills of 5 race on a 100-share order: exactly 20 apply and the order ends FILLED, never overfilled. */
    @Test
    void concurrentFillsNeverOverfill() throws Exception {
        String id = create(LIMIT_BUY, "k1").id();
        List<Res> results = race(30, () -> call("POST", "/orders/" + id + "/fills", "{\"quantity\":5,\"price\":\"190\"}"));
        assertThat(results).filteredOn(r -> r.status == 200).hasSize(20);
        // 5 divides 100, so the late fills all find a FILLED order.
        assertThat(results).filteredOn(r -> r.status != 200).extracting(Res::status).containsOnly(409);
        JsonNode o = call("GET", "/orders/" + id, null).body;
        assertThat(o.get("filledQuantity").asLong()).isEqualTo(100);
        assertThat(o.get("status").asText()).isEqualTo("FILLED");
        assertThat(count("order_events")).isEqualTo(21);
    }

    /** A cancel racing fills: whatever wins, filled + canceled quantity equals the order quantity. */
    @Test
    void cancelRacingFillsStaysConsistent() throws Exception {
        for (int round = 0; round < 5; round++) {
            String id = create(LIMIT_BUY, "k" + round).id();
            List<Callable<Res>> tasks = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                tasks.add(() -> call("POST", "/orders/" + id + "/fills", "{\"quantity\":7,\"price\":\"190\"}"));
            }
            tasks.add(5, () -> call("POST", "/orders/" + id + "/cancel", null));
            raceAll(tasks);
            JsonNode o = call("GET", "/orders/" + id, null).body;
            JsonNode ev = call("GET", "/orders/" + id + "/events", null).body;
            JsonNode last = ev.get(ev.size() - 1);
            long filled = o.get("filledQuantity").asLong();
            assertThat(o.get("status").asText()).isIn("CANCELED", "PARTIALLY_FILLED");
            if (o.get("status").asText().equals("CANCELED")) {
                assertThat(last.get("type").asText()).isEqualTo("CANCELED");
                assertThat(filled + last.get("detail").get("canceledQuantity").asLong()).isEqualTo(100);
            }
            assertThat(ev.size()).isEqualTo(o.get("version").asInt());
        }
    }

    List<Res> race(int n, Callable<Res> task) throws Exception {
        List<Callable<Res>> tasks = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            tasks.add(task);
        }
        return raceAll(tasks);
    }

    /** Starts every task at once behind a latch so the requests overlap. */
    List<Res> raceAll(List<Callable<Res>> tasks) throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Res>> fs = new ArrayList<>();
            for (Callable<Res> t : tasks) {
                fs.add(pool.submit(() -> {
                    go.await();
                    return t.call();
                }));
            }
            go.countDown();
            List<Res> out = new ArrayList<>();
            for (Future<Res> f : fs) {
                out.add(f.get());
            }
            return out;
        }
    }
}
