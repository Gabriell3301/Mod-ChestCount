package com.chestcount.mod;

import com.chestcount.mod.model.*;
import com.chestcount.mod.service.ChestRegistryService;
import com.chestcount.mod.storage.JsonStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class IdentifiersTest {
    @TempDir Path temp;
    final ChestRegistryService registry=new ChestRegistryService();
    ChestRecord add(int x) { return registry.register(new ChestIdentity("world","minecraft:overworld",List.of(new Position(x,64,0)))); }
    @Test void generatedNamesSurviveRestartWithoutCollision() throws Exception {
        var a=add(0); assertEquals("Bau_1",a.name()); add(1);
        var storage=new JsonStorageService(temp); storage.save("world",registry.all());
        registry.load(storage.load("world")); assertEquals("Bau_3",add(2).name());
        assertEquals(a,registry.get("Bau_1","world"));
    }
    @Test void resolvesNamesFullIdsAndUniquePrefixes() {
        var a=add(0); registry.rename(a.id(),"world","Minerios");
        assertEquals(a.id(),registry.get(" minerios ","world").id());
        assertEquals(a.id(),registry.get(a.id(),"world").id());
        assertEquals(a.id(),registry.get(a.id().substring(0,8),"world").id());
    }
    @Test void renameIsImmediateAndNamesAreNormalized() {
        var a=add(0); registry.rename(a.id(),"world","Minerios"); registry.rename("Minerios","world","  Bau\u0301 de Minérios  ");
        assertEquals(a.id(),registry.get("Baú de Minérios","world").id());
        assertThrows(IllegalArgumentException.class,() -> registry.get("Minerios","world"));
    }
    @Test void rejectsDuplicatesAcrossDimensionsButAllowsOtherWorlds() {
        var a=add(0); var b=registry.register(new ChestIdentity("world","minecraft:the_nether",List.of(new Position(0,64,0))));
        registry.rename(a.id(),"world","Minerios");
        assertThrows(IllegalArgumentException.class,() -> registry.rename(b.id(),"world"," minerios "));
        var c=registry.register(new ChestIdentity("other","minecraft:overworld",List.of(new Position(0,64,0))));
        registry.rename(c.id(),"other","Minerios");
        assertEquals(b.name(),registry.get(b.id(),"world").name());
    }
    @Test void preservesLegacyDuplicatesAndOffersResolvableSuggestions() throws Exception {
        var a=add(0); var b=add(1);
        registry.load(List.of(a.rename("Same"),b.rename("same")));
        assertThrows(IllegalArgumentException.class,() -> registry.get("Same","world"));
        for(var suggestion:registry.suggestions("world")) assertNotNull(registry.get(suggestion,"world"));
        var storage=new JsonStorageService(temp); storage.save("world",registry.all());
        registry.load(storage.load("world")); assertEquals(a.id(),registry.get(a.id(),"world").id());
        assertEquals("Same",registry.get(a.id(),"world").name());
    }
    @Test void ambiguousPrefixesFailAndUuidLookingNamesCannotShadowOtherRecords() {
        var a=add(0); var b=add(1);
        var first=new ChestRecord("12345678-0000-0000-0000-000000000001",a.identity(),"A",a.createdAt(),null,List.of(),false);
        var second=new ChestRecord("12345678-0000-0000-0000-000000000002",b.identity(),"B",b.createdAt(),null,List.of(),false);
        registry.load(List.of(first,second));
        assertThrows(IllegalArgumentException.class,() -> registry.get("12345678","world"));
        assertThrows(IllegalArgumentException.class,() -> registry.rename(first.id(),"world",second.id().substring(0,9)));
        assertEquals(2,registry.suggestions("world").size());
    }
    @Test void suggestionsPreferCurrentNames() {
        var a=add(0); registry.rename(a.id(),"world","Madeira");
        assertEquals(List.of("Madeira"),registry.suggestions("world"));
        registry.rename("Madeira","world","Baú de Minérios");
        assertEquals(List.of("Baú de Minérios"),registry.suggestions("world"));
    }
    @Test void legacyNameConflictingWithFullUuidIsNeverSilentlySelected() {
        var a=add(0); var b=add(1);
        registry.load(List.of(a,b.rename(a.id())));
        assertThrows(IllegalArgumentException.class,() -> registry.get(a.id(),"world"));
        assertEquals(a.id(),registry.get("uuid:"+a.id(),"world").id());
        for(var suggestion:registry.suggestions("world")) assertNotNull(registry.get(suggestion,"world"));
        registry.rename("uuid:"+a.id(),"world","Resolved");
        assertEquals(a.id(),registry.get("Resolved","world").id());
        registry.remove("uuid:"+a.id(),"world"); assertEquals(1,registry.all().size());
    }
    @Test void rejectsEmptyIdentifierAndInvalidNames() {
        var a=add(0);
        assertThrows(IllegalArgumentException.class,() -> registry.get("  ","world"));
        for(var name:List.of(" ","x".repeat(129),"UUID:reserved")) assertThrows(IllegalArgumentException.class,() -> registry.rename(a.id(),"world",name));
    }
    @Test void namedSnapshotAndStableIdentitySurviveReload() throws Exception {
        var a=add(0); registry.rename(a.id(),"world","Minérios");
        registry.snapshot(a.id(),a.identity(),List.of(new ItemRecord(26,"minecraft:diamond",42)));
        registry.invalidate(a.id()); var expected=registry.all();
        var storage=new JsonStorageService(temp); storage.save("world",expected);
        registry.load(storage.load("world")); assertEquals(expected,registry.all());
        assertEquals(a.id(),registry.get("minérios","world").id());
        assertEquals(42,registry.get("minérios","world").total());
        assertTrue(registry.get("minérios","world").structureChanged());
    }
}
