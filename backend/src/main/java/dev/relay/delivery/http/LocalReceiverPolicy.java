package dev.relay.delivery.http;

import java.net.URI;

/** Default-off exception for one exact literal-loopback receiver, only in the development profile. */
public final class LocalReceiverPolicy {
    private final boolean enabled;
    private final URI receiver;

    public LocalReceiverPolicy(boolean developmentProfile, boolean allow, String receiverUrl) {
        boolean configured = receiverUrl != null && !receiverUrl.isBlank();
        if ((allow || configured) && !developmentProfile) {
            throw new IllegalArgumentException("Local receiver configuration requires the development profile");
        }
        if (allow && !configured) {
            throw new IllegalArgumentException("An exact local receiver URL is required");
        }
        URI parsed = null;
        if (configured) {
            try {
                parsed = URI.create(receiverUrl);
                String host = parsed.getHost();
                if (!receiverUrl.equals(receiverUrl.strip()) || receiverUrl.length() > 2048
                        || !parsed.isAbsolute() || parsed.isOpaque()
                        || !("http".equalsIgnoreCase(parsed.getScheme()) || "https".equalsIgnoreCase(parsed.getScheme()))
                        || host == null || !literalLoopback(host)
                        || parsed.getRawUserInfo() != null || parsed.getRawFragment() != null
                        || parsed.getPort() <= 0 || parsed.getPort() > 65535) {
                    throw new IllegalArgumentException();
                }
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("Invalid local receiver configuration");
            }
        }
        enabled = developmentProfile && allow;
        receiver = parsed;
    }

    public boolean allows(URI url) {
        // URI.equals performs some normalization. Exact text deliberately grants no aliases,
        // alternate paths, ports, queries, percent encodings or redirect destinations.
        return enabled && url != null && receiver.toString().equals(url.toString());
    }

    static boolean literalLoopback(String host) {
        return "127.0.0.1".equals(host) || "[::1]".equals(host) || "::1".equals(host);
    }

    @Override
    public String toString() {
        return "LocalReceiverPolicy[enabled=" + enabled + "]";
    }
}
