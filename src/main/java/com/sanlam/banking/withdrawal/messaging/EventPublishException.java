package com.sanlam.banking.withdrawal.messaging;

/**
 * A failed publish, classified by whether retrying it could ever succeed.
 *
 * <p>The distinction is the whole point. An attempt counter alone cannot tell
 * a malformed message apart from a broker outage, so it has to treat them the
 * same - and whichever way it is tuned, one of the two is handled wrongly. Tune
 * it low and a brief outage discards good events; tune it high and a genuinely
 * undeliverable message is retried forever.
 *
 * <p>Publishers own this classification because they own the error taxonomy of
 * the transport they speak. The relay stays free of SDK types.
 */
public class EventPublishException extends RuntimeException {

    public enum Kind {
        /**
         * This message will never publish, however many times it is tried: it is
         * malformed, oversized, or otherwise rejected on its own merits. Retrying
         * wastes calls and delays everything behind it.
         */
        PERMANENT,

        /**
         * The message is fine and the transport is not currently able to take it -
         * throttling, a broker fault, a network failure, or a misconfiguration
         * such as a wrong topic ARN or expired credentials.
         *
         * <p>Configuration errors sit here deliberately. They will not resolve on
         * their own, but they affect every event rather than one, so discarding
         * on them would destroy the entire stream over a typo. The right response
         * is to hold the events and raise the backlog, which is exactly what the
         * pending-age gauge measures.
         */
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
