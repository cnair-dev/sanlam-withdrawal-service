package com.sanlam.banking.withdrawal.messaging;

/**
 * A failed publish, classified by whether retrying it could ever succeed.
 *
 * <p>An attempt counter alone cannot tell a malformed message from a broker outage, so
 * whichever way it is tuned one of the two is handled wrongly: low discards good events
 * during a blip, high retries an undeliverable message forever.
 *
 * <p>Publishers own the classification because they own the error taxonomy of the
 * transport they speak. The relay stays free of SDK types.
 */
public class EventPublishException extends RuntimeException {

    public enum Kind {
        /** Rejected on its own merits - malformed, oversized. Retrying cannot help. */
        PERMANENT,

        /** The message is fine and the transport cannot currently take it. */
        TRANSIENT
    }

    private final Kind kind;

    public EventPublishException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    public boolean isPermanent() {
        return kind == Kind.PERMANENT;
    }
}
