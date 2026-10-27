package dev.relay.delivery;

/** Delivery states accepted by the optional event-list status filter. */
public enum DeliveryStatus {
    PENDING,
    IN_FLIGHT,
    RETRY_WAIT,
    SUCCEEDED,
    FAILED
}
