package com.travelpay.gateway.request;

import java.net.InetSocketAddress;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientIpResolverTest {

    private static final ClientIpResolver RESOLVER = new ClientIpResolver(new ClientIpProperties(
            List.of("173.245.48.0/20", "2400:cb00::/32"), List.of("172.18.0.0/16")));

    @Test
    void aCloudflareConnectionUsesTheIpCloudflareSent() {
        assertEquals("203.0.113.7", RESOLVER.resolve(address("173.245.48.10"), List.of("203.0.113.7")));
        assertEquals("2001:db8::7", RESOLVER.resolve(address("2400:cb00::1"), List.of(" 2001:db8::7 ")));
    }

    @Test
    void aConnectionFromOurProxyUsesTheIpCloudflareSent() {
        assertEquals("203.0.113.7", RESOLVER.resolve(address("172.18.0.5"), List.of("203.0.113.7")));
    }

    @Test
    void aDirectConnectionIgnoresAForgedCloudflareHeader() {
        assertEquals("198.51.100.9", RESOLVER.resolve(address("198.51.100.9"), List.of("203.0.113.7")));
        assertEquals("127.0.0.1", RESOLVER.resolve(address("127.0.0.1"), List.of("203.0.113.7")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "not-an-ip", "203.0.113.7, 198.51.100.1", "cloudflare.example.com", "999.1.1.1"})
    void aCloudflareConnectionWithoutAUsableIpFallsBackToTheConnectionAddress(String header) {
        assertEquals("173.245.48.10", RESOLVER.resolve(address("173.245.48.10"), List.of(header)));
    }

    @Test
    void twoCloudflareIpValuesFallBackToTheConnectionAddress() {
        assertEquals("173.245.48.10",
                RESOLVER.resolve(address("173.245.48.10"), List.of("203.0.113.7", "198.51.100.1")));
    }

    @Test
    void aCloudflareConnectionWithoutTheHeaderUsesTheConnectionAddress() {
        assertEquals("173.245.48.10", RESOLVER.resolve(address("173.245.48.10"), List.of()));
    }

    @Test
    void noRangesMeansTheConnectionAddressIsAlwaysUsed() {
        var resolver = new ClientIpResolver(new ClientIpProperties(null, null));

        assertEquals("173.245.48.10", resolver.resolve(address("173.245.48.10"), List.of("203.0.113.7")));
    }

    @Test
    void anUnknownConnectionAddressGivesNoClientIp() {
        assertNull(RESOLVER.resolve(null, List.of("203.0.113.7")));
        assertNull(RESOLVER.resolve(InetSocketAddress.createUnresolved("somewhere", 80), List.of("203.0.113.7")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-range", "10.0.0.0/33", "10.0.0.0"})
    void aWrongRangeStopsStartupWithTheValue(String range) {
        var exception = assertThrows(IllegalArgumentException.class,
                () -> new ClientIpResolver(new ClientIpProperties(List.of(range), List.of())));

        assertTrue(exception.getMessage().contains(range), exception.getMessage());
    }

    private static InetSocketAddress address(String ip) {
        return new InetSocketAddress(ip, 443);
    }
}
