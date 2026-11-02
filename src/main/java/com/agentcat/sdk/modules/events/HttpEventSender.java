package com.agentcat.sdk.modules.events;

import com.agentcat.api.model.PublishEventRequest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Posts events to {@code {baseUrl}/v1/public/events/} with the JDK HTTP client,
 * so the SDK adds no HTTP or JSON library to the customer's classpath.
 *
 * <p>Each request has one total time limit, response body included, so one slow
 * or stalled call cannot hold a worker for longer than {@link #REQUEST_TIMEOUT}.
 */
public final class HttpEventSender implements EventSender {

    static final String EVENTS_PATH = "/v1/public/events/";
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final URI endpoint;
    private final HttpClient client;
    private final Duration requestTimeout;

    public HttpEventSender(String baseUrl) {
        this(baseUrl, REQUEST_TIMEOUT);
    }

    /** For tests: a shorter total deadline. */
    HttpEventSender(String baseUrl, Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.endpoint = URI.create(base + EVENTS_PATH);
        this.client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public void send(PublishEventRequest request) throws EventSendException {
        HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(request.toJson(), StandardCharsets.UTF_8))
                .build();
        // One total deadline, through the end of the body: HttpRequest.timeout() alone
        // stops at the response headers, so a stalled body could hold this worker forever.
        CompletableFuture<HttpResponse<String>> pending =
                client.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        HttpResponse<String> response;
        try {
            response = pending.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.cancel(true);
            throw new EventSendException(-1, "timed out after " + requestTimeout.toMillis() + " ms", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new EventSendException(-1, describe(cause), cause);
        } catch (InterruptedException e) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new EventSendException(-1, "interrupted", e);
        }
        int status = response.statusCode();
        if (status < 200 || status > 299) {
            String text = response.body();
            throw new EventSendException(status,
                    "HTTP " + status + (text == null || text.isBlank() ? "" : ": " + text), null);
        }
    }

    private static String describe(Throwable e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }
}
