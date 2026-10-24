package com.flashgrid;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manual load test. Start the FlashGrid application first, then run this class's main().
 * It creates a sale with STOCK units and fires USERS simultaneous purchase requests
 * from different users. Expected: exactly STOCK requests succeed (201), the rest fail (410).
 */
public class ConcurrencyTestRunner {

    private static final String BASE = "http://localhost:8080/api/flash-sales";
    private static final int STOCK = 10;
    private static final int USERS = 200;

    public static void main(String[] args) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        // 1. Create the flash sale
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        String createBody = """
                {"title":"Load Test Item","stock":%d,"price":499.00,"startTime":"%s","endTime":"%s"}
                """.formatted(STOCK, now.minusMinutes(1), now.plusHours(1));

        HttpResponse<String> created = client.send(
                HttpRequest.newBuilder(URI.create(BASE))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(createBody))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        Matcher matcher = Pattern.compile("\"id\"\\s*:\\s*(\\d+)").matcher(created.body());
        if (created.statusCode() != 201 || !matcher.find()) {
            System.out.println("Could not create sale. Status " + created.statusCode() + ": " + created.body());
            return;
        }
        long itemId = Long.parseLong(matcher.group(1));
        System.out.println("Created item " + itemId + " with stock " + STOCK);

        // 2. Fire USERS purchase requests at the same instant
        ExecutorService pool = Executors.newFixedThreadPool(USERS);
        CountDownLatch ready = new CountDownLatch(USERS);
        CountDownLatch go = new CountDownLatch(1);
        Map<Integer, AtomicInteger> statusCounts = new ConcurrentHashMap<>();

        for (int i = 1; i <= USERS; i++) {
            long userId = 1000L + i;
            Callable<Void> task = () -> {
                ready.countDown();
                go.await();
                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder(URI.create(BASE + "/" + itemId + "/purchase"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString("{\"userId\":" + userId + "}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                statusCounts.computeIfAbsent(response.statusCode(), k -> new AtomicInteger()).incrementAndGet();
                return null;
            };
            pool.submit(task);
        }

        ready.await();
        long start = System.nanoTime();
        go.countDown();
        pool.shutdown();
        pool.awaitTermination(60, TimeUnit.SECONDS);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        // 3. Report
        System.out.println();
        System.out.println("Finished " + USERS + " requests in " + elapsedMs + " ms");
        System.out.println("Status counts: " + new TreeMap<>(statusCounts));

        HttpResponse<String> stock = client.send(
                HttpRequest.newBuilder(URI.create(BASE + "/" + itemId + "/stock")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        System.out.println("Redis stock now: " + stock.body());

        int succeeded = statusCounts.getOrDefault(201, new AtomicInteger()).get();
        System.out.println(succeeded == STOCK
                ? "PASS: exactly " + STOCK + " orders created, no overselling."
                : "FAIL: expected " + STOCK + " orders but got " + succeeded);
    }
}