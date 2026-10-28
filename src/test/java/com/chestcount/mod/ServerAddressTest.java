package com.chestcount.mod;

import com.chestcount.mod.service.ServerAddressNormalizer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ServerAddressTest {
    @Test void defaultPortCaseAndTrailingDot() { assertEquals("example.org",ServerAddressNormalizer.normalize(" EXAMPLE.ORG.:025565 ")); }
    @Test void preservesDifferentPorts() { assertEquals("example.org:25566",ServerAddressNormalizer.normalize("Example.org:25566")); }
    @Test void ipv6DoesNotCollapseIntoFirstSegment() { assertEquals("[2001:db8::1]",ServerAddressNormalizer.normalize("2001:DB8::1")); assertEquals("[2001:db8::1]",ServerAddressNormalizer.normalize("[2001:db8::1]:25565")); assertNotEquals(ServerAddressNormalizer.normalize("2001:db8::1"),ServerAddressNormalizer.normalize("2001:db8::2")); }
    @Test void ipv6NonDefaultPort() { assertEquals("[::1]:25566",ServerAddressNormalizer.normalize("[::1]:25566")); }
    @Test void invalidPortRejected() { assertThrows(IllegalArgumentException.class,() -> ServerAddressNormalizer.normalize("example.org:70000")); }
}
