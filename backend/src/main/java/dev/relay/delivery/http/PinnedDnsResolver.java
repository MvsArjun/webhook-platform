package dev.relay.delivery.http;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.apache.hc.client5.http.DnsResolver;

/** Per-request immutable addresses: neither connection nor canonical-name resolution queries DNS. */
final class PinnedDnsResolver implements DnsResolver {
    private final String host;
    private final InetAddress[] addresses;

    PinnedDnsResolver(String host, InetAddress[] addresses) {
        this.host = DestinationPolicy.normalizedHost(host);
        this.addresses = addresses.clone();
    }

    @Override
    public InetAddress[] resolve(String requestedHost) throws UnknownHostException {
        requireHost(requestedHost);
        return addresses.clone();
    }

    @Override
    public String resolveCanonicalHostname(String requestedHost) throws UnknownHostException {
        requireHost(requestedHost);
        return host;
    }

    private void requireHost(String requestedHost) throws UnknownHostException {
        if (requestedHost == null || !host.equals(DestinationPolicy.normalizedHost(requestedHost))) {
            throw new UnknownHostException("Unvalidated destination");
        }
    }
}
