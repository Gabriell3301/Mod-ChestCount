package com.chestcount.mod;
import com.chestcount.mod.integration.DeviceCredentials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class DeviceCredentialsTest {
    @TempDir Path root;
    @Test void persistsPinsEndpointAndRedactsDiagnosticText() throws Exception {
        DeviceCredentials.initialize(root);
        var value=new DeviceCredentials.Credential("B".repeat(43),"device","2030-01-01T00:00:00Z",false);
        DeviceCredentials.save("https://example.test/api/v1/sync",value);
        assertEquals(value,DeviceCredentials.load("https://example.test/api/v1/sync"));
        assertNull(DeviceCredentials.load("https://other.test/api/v1/sync"));
        assertFalse(value.toString().contains(value.token()));
        DeviceCredentials.initialize(root);
        assertEquals(value.token(),DeviceCredentials.token("https://example.test/api/v1/sync"));
    }
    @Test void pendingDisconnectPreventsUploadsAndLegacyFallback() throws Exception {
        DeviceCredentials.initialize(root); String endpoint="https://example.test/api/v1/sync";
        DeviceCredentials.save(endpoint,new DeviceCredentials.Credential("B".repeat(43),"device","2030-01-01T00:00:00Z",true));
        assertThrows(IllegalStateException.class,()->DeviceCredentials.token(endpoint));
        DeviceCredentials.markDisconnected(endpoint); DeviceCredentials.clear(endpoint);
        assertNull(DeviceCredentials.load(endpoint));
        assertThrows(IllegalStateException.class,()->DeviceCredentials.token(endpoint));
    }
}
