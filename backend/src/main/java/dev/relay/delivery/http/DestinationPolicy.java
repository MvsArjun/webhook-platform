package dev.relay.delivery.http;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

/** Conservative destination policy. Every returned DNS address must pass before any connection. */
final class DestinationPolicy {
    private static final Set<String> INTERNAL_SUFFIXES = Set.of("localhost", "local", "internal", "home",
            "lan", "localdomain", "test", "invalid", "example", "onion", "alt", "arpa");

    private DestinationPolicy() {}

    static Target validate(URI url, LocalReceiverPolicy local) throws BlockedDestinationException {
        if (url == null || !url.isAbsolute() || url.isOpaque() || url.toString().length() > 2048
                || url.getHost() == null || url.getRawUserInfo() != null || url.getRawFragment() != null
                || url.getPort() == 0 || url.getPort() > 65535) {
            throw blocked();
        }
        String host = normalizedHost(url.getHost());
        if (host.isBlank() || host.chars().anyMatch(character -> character > 127) || host.contains("%")) {
            throw blocked();
        }
        if (host.contains(":")) {
            try {
                // URI.getHost has already parsed this as an IPv6 literal, so this cannot
                // perform DNS. Java otherwise normalizes IPv4-mapped literals to Inet4Address.
                if (InetAddress.getByName(host).getAddress().length != 16) throw blocked();
            } catch (UnknownHostException invalidLiteral) {
                throw blocked();
            }
        }
        if (local.allows(url)) {
            return new Target(host, true);
        }
        if (!"https".equalsIgnoreCase(url.getScheme())) {
            throw blocked();
        }
        String name = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
        // IPv6 literals contain colons; numeric IPv4 literals contain dots. Odd numeric hosts
        // remain subject to validated DNS results rather than being trusted as public addresses.
        if (!name.contains(":") && !name.contains(".")) {
            throw blocked();
        }
        for (String suffix : INTERNAL_SUFFIXES) {
            if (name.equals(suffix) || name.endsWith("." + suffix)) {
                throw blocked();
            }
        }
        return new Target(host, false);
    }

    static InetAddress[] validateAddresses(InetAddress[] addresses, boolean local) throws BlockedDestinationException {
        if (addresses == null || addresses.length == 0 || addresses.length > 64) {
            throw blocked();
        }
        for (InetAddress address : addresses) {
            if (address == null || (local ? !address.isLoopbackAddress() : !publicAddress(address))) {
                throw blocked();
            }
        }
        return addresses.clone();
    }

    static boolean publicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int a = bytes[0] & 255;
            int b = bytes[1] & 255;
            int c = bytes[2] & 255;
            return !(a == 0 || a == 10 || a == 127 || a >= 224
                    || (a == 100 && b >= 64 && b <= 127)
                    || (a == 169 && b == 254) || (a == 172 && b >= 16 && b <= 31)
                    || (a == 192 && ((b == 0 && (c == 0 || c == 2)) || (b == 31 && c == 196)
                        || (b == 52 && c == 193) || (b == 88 && c == 99) || b == 168 || (b == 175 && c == 48)))
                    || (a == 198 && (b == 18 || b == 19 || (b == 51 && c == 100)))
                    || (a == 203 && b == 0 && c == 113)
                    || (a == 168 && b == 63 && c == 129 && (bytes[3] & 255) == 16));
        }
        if (bytes.length != 16 || (bytes[0] & 0xe0) != 0x20) {
            // Only global-unicast 2000::/3 can pass. This excludes mapped/compatible IPv4,
            // NAT64, unique-local, link-local, multicast and scoped/special-use families.
            return false;
        }
        int second = ((bytes[2] & 255) << 8) | (bytes[3] & 255);
        boolean ietfSpecial = (bytes[0] & 255) == 0x20 && (bytes[1] & 255) == 0x01
                && (second <= 0x01ff || second == 0x0db8);
        boolean sixToFour = (bytes[0] & 255) == 0x20 && (bytes[1] & 255) == 0x02;
        boolean documentation = (bytes[0] & 255) == 0x3f && (bytes[1] & 0xf0) == 0xf0;
        boolean as112 = (bytes[0] & 255) == 0x26 && (bytes[1] & 255) == 0x20
                && second == 0x004f && (bytes[4] & 255) == 0x80 && bytes[5] == 0;
        return !ietfSpecial && !sixToFour && !documentation && !as112;
    }

    static String normalizedHost(String host) {
        String result = host.toLowerCase(Locale.ROOT);
        return result.startsWith("[") && result.endsWith("]")
                ? result.substring(1, result.length() - 1) : result;
    }

    private static BlockedDestinationException blocked() {
        return new BlockedDestinationException();
    }

    record Target(String host, boolean local) {}

    static final class BlockedDestinationException extends IOException {
        BlockedDestinationException() { super("Destination is not permitted"); }
    }
}
