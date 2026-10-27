package dev.relay.delivery.http;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DestinationPolicyTest {
    private final LocalReceiverPolicy production = new LocalReceiverPolicy(false, false, "");

    @Test
    void rejectsPrivateAndSpecialIpv4AndIpv6Families() throws Exception {
        for (String literal : List.of("0.0.0.0", "10.1.2.3", "100.64.0.1", "127.0.0.1", "169.254.169.254",
                "172.16.0.1", "192.0.0.8", "192.0.2.1", "192.31.196.1", "192.52.193.1", "192.88.99.1",
                "192.168.1.1", "192.175.48.1", "198.18.0.1", "198.51.100.1", "203.0.113.1",
                "224.0.0.1", "255.255.255.255", "168.63.129.16", "::", "::1", "::ffff:127.0.0.1",
                "64:ff9b::808:808", "100::1", "2001::1", "2001:2::1", "2001:db8::1", "2002:0808:0808::1",
                "2620:4f:8000::1", "3fff::1", "fc00::1", "fe80::1", "ff02::1")) {
            assertThat(DestinationPolicy.publicAddress(InetAddress.getByName(literal))).as(literal).isFalse();
        }
        for (String literal : List.of("8.8.8.8", "93.184.216.34", "2001:4860:4860::8888", "2606:4700:4700::1111")) {
            assertThat(DestinationPolicy.publicAddress(InetAddress.getByName(literal))).as(literal).isTrue();
        }
    }

    @Test
    void rejectsEntireMixedDnsAnswerBeforeUsingAnyPublicAddress() throws Exception {
        InetAddress[] mixed = {InetAddress.getByName("8.8.8.8"), InetAddress.getByName("127.0.0.1")};
        assertThatThrownBy(() -> DestinationPolicy.validateAddresses(mixed, false))
                .isInstanceOf(DestinationPolicy.BlockedDestinationException.class);
        assertThatThrownBy(() -> DestinationPolicy.validateAddresses(new InetAddress[0], false))
                .isInstanceOf(DestinationPolicy.BlockedDestinationException.class);
    }

    @Test
    void rejectsInternalNamesAndUnsafeUriFormsWithoutResolution() {
        for (String value : List.of("http://public.company.com/hook", "https://localhost/", "https://LOCALHOST./",
                "https://service.internal/", "https://service.local/", "https://service.home.arpa/",
                "https://service/", "https://2130706433/", "https://user:password@public.company.com/",
                "https://public.company.com/#fragment", "https://public.company.com:0/",
                "https://public.company.com:65536/", "https:opaque", "https://[fe80::1%25eth0]/")) {
            assertThatThrownBy(() -> DestinationPolicy.validate(URI.create(value), production)).as(value)
                    .isInstanceOf(DestinationPolicy.BlockedDestinationException.class);
        }
    }

    @Test
    void developmentExceptionRequiresProfileFlagAndOneExactLiteralUrl() throws Exception {
        URI configured = URI.create("http://127.0.0.1:18080/receiver?token=development-only");
        var local = new LocalReceiverPolicy(true, true, configured.toString());
        assertThat(local.allows(configured)).isTrue();
        assertThat(DestinationPolicy.validate(configured, local).local()).isTrue();
        for (String other : List.of("http://127.0.0.1:18080/receiver", "http://127.0.0.1:18080/other?token=development-only",
                "http://127.0.0.1:18081/receiver?token=development-only", "http://localhost:18080/receiver?token=development-only",
                "http://127.0.0.1:18080/%72eceiver?token=development-only")) {
            assertThat(local.allows(URI.create(other))).as(other).isFalse();
        }
        assertThat(new LocalReceiverPolicy(true, false, configured.toString()).allows(configured)).isFalse();
        assertThatThrownBy(() -> new LocalReceiverPolicy(false, true, configured.toString())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LocalReceiverPolicy(false, false, configured.toString())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LocalReceiverPolicy(true, true, "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LocalReceiverPolicy(true, false, "not a URL")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LocalReceiverPolicy(true, true, "http://localhost:18080/receiver")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LocalReceiverPolicy(true, true, "http://10.0.0.1/receiver")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LocalReceiverPolicy(true, true, "http://127.0.0.1/receiver")).isInstanceOf(IllegalArgumentException.class);
        assertThat(local.toString()).doesNotContain("development-only", "127.0.0.1");
    }

    @Test
    void ipv6LoopbackExceptionIsExactAndStillRequiresLiteralLoopback() throws Exception {
        URI receiver = URI.create("http://[::1]:18080/receiver");
        var local = new LocalReceiverPolicy(true, true, receiver.toString());
        assertThat(DestinationPolicy.validate(receiver, local).host()).isEqualTo("::1");
        assertThat(local.allows(URI.create("http://[0:0:0:0:0:0:0:1]:18080/receiver"))).isFalse();
    }

    @Test
    void pinnedResolverCannotRebindThroughMutationAnotherHostOrCanonicalLookup() throws Exception {
        InetAddress trusted = InetAddress.getByName("8.8.8.8");
        InetAddress[] original = {trusted};
        var pinned = new PinnedDnsResolver("receiver.company.com", DestinationPolicy.validateAddresses(original, false));
        original[0] = InetAddress.getByName("127.0.0.1");
        InetAddress[] first = pinned.resolve("RECEIVER.COMPANY.COM");
        assertThat(first).containsExactly(trusted);
        first[0] = original[0];
        assertThat(pinned.resolve("receiver.company.com")).containsExactly(trusted);
        assertThat(pinned.resolveCanonicalHostname("receiver.company.com")).isEqualTo("receiver.company.com");
        assertThatThrownBy(() -> pinned.resolve("attacker.company.com")).isInstanceOf(UnknownHostException.class);
        assertThatThrownBy(() -> pinned.resolveCanonicalHostname("attacker.company.com")).isInstanceOf(UnknownHostException.class);
    }
}
