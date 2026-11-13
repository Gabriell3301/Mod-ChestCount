package com.chestcount.mod;
import com.chestcount.mod.service.SaveWorldIdentity;
import com.chestcount.mod.storage.JsonStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class SaveWorldIdentityTest {
    @TempDir Path root;
    @Test void upgradeRestartAndRenameKeepIdentity() throws Exception {
        Path original=Files.createDirectory(root.resolve("Mundo 1"));
        String id=SaveWorldIdentity.load(original);
        assertEquals("local:"+JsonStorageService.key(original.toAbsolutePath().normalize().toString()),id);
        assertEquals(id,SaveWorldIdentity.load(original));
        Path renamed=Files.move(original,root.resolve("Minha base"));
        assertEquals(id,SaveWorldIdentity.load(renamed));
    }
    @Test void copiedSaveGetsIndependentStableIdentity() throws Exception {
        Path original=Files.createDirectory(root.resolve("Original")); String first=SaveWorldIdentity.load(original);
        Path copy=Files.createDirectory(root.resolve("Copy")); Files.copy(original.resolve("chestcount-world-id.json"),copy.resolve("chestcount-world-id.json"));
        String second=SaveWorldIdentity.load(copy);
        assertNotEquals(first,second); assertEquals(second,SaveWorldIdentity.load(copy)); assertEquals(first,SaveWorldIdentity.load(original));
    }
    @Test void corruptMetadataNeverSilentlyChangesIdentity() throws Exception {
        Path save=Files.createDirectory(root.resolve("Save")); Files.writeString(save.resolve("chestcount-world-id.json"),"broken");
        assertThrows(java.io.IOException.class,()->SaveWorldIdentity.load(save));
        assertEquals("broken",Files.readString(save.resolve("chestcount-world-id.json")));
    }
}
