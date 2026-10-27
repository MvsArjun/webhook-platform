package dev.relay.system;

public record SystemStatus(String service, String role, int phase, String database) {
    public static SystemStatus connected() {
        return new SystemStatus("relay", "api", 7, "connected");
    }
}

