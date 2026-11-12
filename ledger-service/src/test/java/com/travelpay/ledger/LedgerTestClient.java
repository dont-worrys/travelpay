package com.travelpay.ledger;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 원장 HTTP 요청 도구. 동시 요청은 요청마다 보낸 시각·응답 시각·상태·본문을 남긴다 */
final class LedgerTestClient {

    static final String TRANSACTIONS = "/internal/v1/ledger/transactions";
    static final String CANCEL = TRANSACTIONS + "/cancel";

    private final int port;
    private final JsonMapper jsonMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    LedgerTestClient(int port, JsonMapper jsonMapper) {
        this.port = port;
        this.jsonMapper = jsonMapper;
    }

    // ---- 요청 본문 ----

    static Map<String, Object> user(UUID walletId, String currency, String direction, long amount, String entryType) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("ownerType", "USER");
        entry.put("walletId", walletId.toString());
        entry.put("currency", currency);
        entry.put("direction", direction);
        entry.put("amount", amount);
        entry.put("entryType", entryType);
        return entry;
    }

    static Map<String, Object> system(String accountType, String currency, String direction, long amount,
                                      String entryType) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("ownerType", "SYSTEM");
        entry.put("accountType", accountType);
        entry.put("currency", currency);
        entry.put("direction", direction);
        entry.put("amount", amount);
        entry.put("entryType", entryType);
        return entry;
    }

    /** 충전 본문: CHARGE_SOURCE 차변, 지갑 대변 */
    static Map<String, Object> chargeBody(UUID referenceId, UUID walletId, long amount) {
        return body("CHARGE", referenceId, List.of(
                system("CHARGE_SOURCE", "KRW", "DEBIT", amount, "CHARGE_IN"),
                user(walletId, "KRW", "CREDIT", amount, "CHARGE_IN")));
    }

    /** 송금 본문: from 차변, to 대변 */
    static Map<String, Object> transferBody(UUID referenceId, UUID fromWalletId, UUID toWalletId, long amount) {
        return body("TRANSFER", referenceId, List.of(
                user(fromWalletId, "KRW", "DEBIT", amount, "TRANSFER_OUT"),
                user(toWalletId, "KRW", "CREDIT", amount, "TRANSFER_IN")));
    }

    static Map<String, Object> body(String referenceType, UUID referenceId, List<Map<String, Object>> entries) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("referenceType", referenceType);
        body.put("referenceId", referenceId.toString());
        body.put("entries", entries);
        return body;
    }

    /** 취소 본문: reference만 */
    static Map<String, Object> cancelBody(String referenceType, UUID referenceId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("referenceType", referenceType);
        body.put("referenceId", referenceId.toString());
        return body;
    }

    /** 본문을 복사해 필드 하나를 바꾼다 */
    static Map<String, Object> with(Map<String, Object> body, String field, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(body);
        copy.put(field, value);
        return copy;
    }

    // ---- 요청 ----

    UUID openWallet() throws Exception {
        UUID walletId = UUID.randomUUID();
        HttpResponse<String> response = post("/internal/v1/ledger/accounts",
                jsonMapper.writeValueAsString(Map.of("walletId", walletId)));
        assertEquals(201, response.statusCode(), response.body());
        return walletId;
    }

    /** 지갑 KRW 충전 (201 확인). 거래 ID를 돌려준다 */
    UUID charge(UUID walletId, long amount) throws Exception {
        HttpResponse<String> response = register(chargeBody(UUID.randomUUID(), walletId, amount));
        assertEquals(201, response.statusCode(), response.body());
        return UUID.fromString(data(response).get("transactionId").asString());
    }

    HttpResponse<String> register(Map<String, Object> body) throws Exception {
        return post(TRANSACTIONS, jsonMapper.writeValueAsString(body));
    }

    HttpResponse<String> cancel(Map<String, Object> body) throws Exception {
        return post(CANCEL, jsonMapper.writeValueAsString(body));
    }

    JsonNode data(HttpResponse<String> response) {
        return jsonMapper.readTree(response.body()).get("data");
    }

    JsonNode data(String responseBody) {
        return jsonMapper.readTree(responseBody).get("data");
    }

    String errorCode(String responseBody) {
        JsonNode error = jsonMapper.readTree(responseBody).get("error");
        return error == null || error.get("code") == null ? null : error.get("code").asString();
    }

    HttpResponse<String> post(String path, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * 본문들을 시작 신호에 맞춰 동시에 보낸다. 응답은 {@link Pending#await}로 받는다 (그 사이 잠금 해제 등을 할 수 있게).
     */
    Pending sendConcurrently(List<Map<String, Object>> bodies) throws Exception {
        return sendConcurrently(TRANSACTIONS, bodies);
    }

    /** 같은 경로로 본문들을 동시에 보낸다(취소 동시 요청용) */
    Pending sendConcurrently(String path, List<Map<String, Object>> bodies) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(bodies.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Call>> futures = new ArrayList<>();
        for (int i = 0; i < bodies.size(); i++) {
            int index = i;
            String json = jsonMapper.writeValueAsString(bodies.get(i));
            futures.add(pool.submit(() -> {
                start.await();
                Instant sentAt = Instant.now();
                try {
                    HttpResponse<String> response = post(path, json);
                    return new Call(index, sentAt, Instant.now(), response.statusCode(), response.body(), null);
                } catch (Exception e) {
                    return new Call(index, sentAt, Instant.now(), -1, null, e.getClass().getName());
                }
            }));
        }
        start.countDown();
        return new Pending(pool, futures);
    }

    /** 요청 한 건의 기록 */
    record Call(int index, Instant sentAt, Instant respondedAt, int status, String body, String clientError) {

        long elapsedMs() {
            return Duration.between(sentAt, respondedAt).toMillis();
        }

        @Override
        public String toString() {
            return "#" + index + " sent=" + sentAt + " responded=" + respondedAt + " (" + elapsedMs() + "ms) status="
                    + status + (clientError == null ? "" : " clientError=" + clientError) + " body=" + body;
        }
    }

    static String describe(List<Call> calls) {
        return calls.stream().map(Call::toString).collect(Collectors.joining("\n", "요청별 기록:\n", ""));
    }

    /** 보낸 요청들. await에서 모든 응답을 받는다 */
    static final class Pending {
        private final ExecutorService pool;
        private final List<Future<Call>> futures;

        private Pending(ExecutorService pool, List<Future<Call>> futures) {
            this.pool = pool;
            this.futures = futures;
        }

        List<Call> await(Duration timeout) throws Exception {
            try {
                List<Call> calls = new ArrayList<>();
                for (Future<Call> future : futures) {
                    calls.add(future.get(timeout.toMillis(), TimeUnit.MILLISECONDS));
                }
                return calls;
            } finally {
                pool.shutdownNow();
            }
        }
    }
}
