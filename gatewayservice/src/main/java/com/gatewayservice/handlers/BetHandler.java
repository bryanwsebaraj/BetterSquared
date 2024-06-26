package com.gatewayservice.server;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.lang.System;
import java.net.HttpURLConnection;
import java.net.URL;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisCluster;
import java.util.concurrent.TimeUnit;

public class BetHandler implements HttpHandler {

    private final String betServiceURL = System.getenv("BET_SERVICE_HOST") + ":" + System.getenv("BET_SERVICE_PORT");
    private Jedis jwtCacheConnection;
    private JedisCluster rateLimiterConnection;
    private final int REQUEST_LIMIT = Integer.parseInt(System.getenv("RL_REQUEST_LIMIT"));// 2; // Maximum requests per window
    private final long TIME_WINDOW_SECONDS = Integer.parseInt(System.getenv("RL_TIME_WINDOW")); // 60; // Time window in seconds

    public BetHandler(Jedis jwtCacheConnection, JedisCluster rateLimiterConnection) {
        this.rateLimiterConnection = rateLimiterConnection;
        this.jwtCacheConnection = jwtCacheConnection;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!isRequestAllowed(exchange)) {
                String errorResponse = "{\"error\": \"Rate limit exceeded\"}";
                exchange.getResponseHeaders().set("Content-Type", "application/json; utf-8");
                exchange.sendResponseHeaders(429, errorResponse.getBytes(StandardCharsets.UTF_8).length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(errorResponse.getBytes(StandardCharsets.UTF_8));
                }
                return;
            }
            // Read request body
            InputStream is = exchange.getRequestBody();
            String requestBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);

            // Validate request
            ValidateRequest validator = new ValidateRequest();
            ValidateRequest.ValidationResult validationResult = validator.validateRequest(exchange, this.jwtCacheConnection);

            if (!validationResult.isValid()) {
                String errorResponse = "{\"error\": \"Unauthorized\"}";
                exchange.getResponseHeaders().set("Content-Type", "application/json; utf-8");
                exchange.sendResponseHeaders(401, errorResponse.getBytes(StandardCharsets.UTF_8).length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(errorResponse.getBytes(StandardCharsets.UTF_8));
                }
                return;
            }

            String username = validationResult.getUsername();

            // Forward request to the group service
            //System.out.println("Routing group request to " + betServiceURL);
            System.out.println("http://" + betServiceURL + exchange.getRequestURI().toString());
            URL url = new URL("http://" + betServiceURL + exchange.getRequestURI().toString());
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            System.out.println(exchange.getRequestMethod());
            conn.setRequestMethod(exchange.getRequestMethod());
            conn.setRequestProperty("Content-Type", "application/json; utf-8");
            conn.setRequestProperty("Username", username);

            if (conn.getRequestMethod().equalsIgnoreCase("POST") || conn.getRequestMethod().equalsIgnoreCase("PUT")) {
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = requestBody.getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }
            }
            System.out.println(conn.getRequestMethod());


            System.out.println("Receiving response from bet service.");
            // Get response from the group service
            int responseCode = conn.getResponseCode();
            System.out.println("Response Code: " + responseCode);
            InputStream authResponseStream = responseCode == 200 ? conn.getInputStream() : conn.getErrorStream();
            String authResponse = new String(authResponseStream.readAllBytes(), StandardCharsets.UTF_8);

            // Set response headers and body
            exchange.getResponseHeaders().set("Content-Type", "application/json; utf-8");
            exchange.sendResponseHeaders(responseCode, authResponse.getBytes(StandardCharsets.UTF_8).length);
            System.out.println("Routing bet response from bet to gateway");
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(authResponse.getBytes(StandardCharsets.UTF_8));
            }

        } catch (Exception e) {
            e.printStackTrace();
            String errorResponse = "{\"error\": \"Bet service response failed.\"}";
            exchange.getResponseHeaders().set("Content-Type", "application/json; utf-8");
            exchange.sendResponseHeaders(500, errorResponse.getBytes(StandardCharsets.UTF_8).length);

            try (OutputStream os = exchange.getResponseBody()) {
                os.write(errorResponse.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private boolean isRequestAllowed(HttpExchange exchange) {
        String clientIP = exchange.getRemoteAddress().getAddress().getHostAddress();
        long currentTimestamp = System.currentTimeMillis() / 1000;
        long windowStart = currentTimestamp - TIME_WINDOW_SECONDS + 1;

        try {
            rateLimiterConnection.zremrangeByScore(clientIP, 0, windowStart - 1);
            long currentRequests = rateLimiterConnection.zcard(clientIP);
            rateLimiterConnection.zadd(clientIP, currentTimestamp, String.valueOf(currentTimestamp));
            rateLimiterConnection.expire(clientIP, (int) TIME_WINDOW_SECONDS);
            return currentRequests <= REQUEST_LIMIT;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }
}

