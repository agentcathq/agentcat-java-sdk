package com.agentcat.sdk.modules.events;

/**
 * A failed delivery. {@link #getStatusCode()} is the HTTP status, or {@code -1}
 * when no response arrived (connection failure or timeout).
 */
public final class EventSendException extends Exception {

    private static final long serialVersionUID = 1L;

    private final int statusCode;

    public EventSendException(int statusCode, String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }

    /** A 4xx status: the API rejected the event, so a retry does not help. */
    public boolean isClientError() {
        return statusCode >= 400 && statusCode <= 499;
    }
}
