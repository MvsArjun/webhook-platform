package dev.relay.delivery.signing;

/** Endpoint signing material is immutable and deliberately absent from diagnostic text. */
public final class SigningKey {
    private final byte[] value;

    public SigningKey(byte[] value) {
        if (value == null || value.length != 32) throw new IllegalArgumentException("Invalid signing key");
        this.value = value.clone();
    }

    public byte[] bytes() {
        return value.clone();
    }

    @Override
    public String toString() {
        return "SigningKey[REDACTED]";
    }
}
