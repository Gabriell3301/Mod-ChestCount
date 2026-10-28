package com.chestcount.mod;

import com.chestcount.mod.model.*;
import com.chestcount.mod.service.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RegistryTest {
    final ChestRegistryService service = new ChestRegistryService();
    final Position a = new Position(0,64,0), b = new Position(1,64,0);
    ChestIdentity single() { return new ChestIdentity("world-a","minecraft:overworld",List.of(a)); }
    ChestIdentity pair() { return new ChestIdentity("world-a","minecraft:overworld",List.of(a,b)); }
    @Test void registersSingle() { var r=service.register(single()); assertEquals(27,r.identity().slots()); assertNull(r.syncedAt()); }
    @Test void doubleCanonicalRegardlessOfClickedHalf() { var r=service.register(pair()); assertEquals(r,service.register(new ChestIdentity("world-a","minecraft:overworld",List.of(b,a)))); assertEquals(54,r.identity().slots()); }
    @Test void duplicateDoesNotChangeRevision() { service.register(single()); var rev=service.revision(); service.register(single()); assertEquals(1,service.all().size()); assertEquals(rev,service.revision()); }
    @Test void worldsAreSeparate() { service.register(single()); service.register(new ChestIdentity("world-b","minecraft:overworld",List.of(a))); assertEquals(1,service.world("world-a").size()); }
    @Test void dimensionsAreSeparate() { service.register(single()); service.register(new ChestIdentity("world-a","minecraft:the_nether",List.of(a))); assertEquals(2,service.all().size()); assertEquals(1,service.nearby("world-a","minecraft:overworld",0,64,0,32).size()); }
    @Test void snapshotReplacesRatherThanAdds() { var r=service.register(single()); service.snapshot(r.id(),single(),List.of(new ItemRecord(0,"minecraft:diamond",32))); service.snapshot(r.id(),single(),List.of(new ItemRecord(1,"minecraft:iron_ingot",4))); assertEquals(4,service.get(r.id(),"world-a").total()); assertTrue(ItemSearchService.find(service.all(),"minecraft:diamond").isEmpty()); }
    @Test void emptySnapshotIsSynced() { var r=service.register(single()); service.snapshot(r.id(),single(),List.of()); assertNotNull(service.get(r.id(),"world-a").syncedAt()); }
    @Test void itemSearchSumsSlotsAndIgnoresUnsynced() { var r=service.register(single()); assertTrue(ItemSearchService.find(service.all(),"minecraft:diamond").isEmpty()); service.snapshot(r.id(),single(),List.of(new ItemRecord(0,"minecraft:diamond",4),new ItemRecord(1,"minecraft:diamond",8))); assertEquals(12,ItemSearchService.find(service.all(),"minecraft:diamond").getFirst().count()); }
    @Test void renameAndSearchByName() { var r=service.register(single()); service.rename(r.id(),"world-a","Armazém"); assertEquals(r.id(),service.byName("world-a","ARMAZÉM").getFirst().id()); }
    @Test void removalDoesNotCrossWorld() { var r=service.register(single()); assertThrows(IllegalArgumentException.class,() -> service.remove(r.id(),"world-b")); service.remove(r.id(),"world-a"); assertTrue(service.all().isEmpty()); }
    @Test void distanceAndRadius() { assertEquals(5,new Position(0,0,0).distance(3.5,4.5,.5)); service.register(single()); assertTrue(service.nearby("world-a","minecraft:overworld",100,64,0,32).isEmpty()); }
    @Test void adjacentSinglesAreNotAutomaticallyMerged() { service.register(single()); service.register(new ChestIdentity("world-a","minecraft:overworld",List.of(b))); assertEquals(2,service.all().size()); }
    @Test void mergedStructureInvalidatesAndPreservesOldData() { var r=service.register(single()); service.snapshot(r.id(),single(),List.of(new ItemRecord(0,"minecraft:diamond",3))); service.observe(pair()); assertTrue(service.get(r.id(),"world-a").structureChanged()); assertEquals(3,service.get(r.id(),"world-a").total()); assertThrows(IllegalStateException.class,() -> service.register(pair())); assertThrows(IllegalStateException.class,() -> service.snapshot(r.id(),single(),List.of())); }
    @Test void splitDoubleBlocksSynchronization() { var r=service.register(pair()); service.observe(single()); assertTrue(service.get(r.id(),"world-a").structureChanged()); assertTrue(service.exact(pair()).isEmpty()); }
    @Test void destroyedHalfIsInvalidatedWithoutLosingSnapshot() { var r=service.register(pair()); service.snapshot(r.id(),pair(),List.of(new ItemRecord(53,"minecraft:diamond",2))); service.invalidate(r.id()); assertTrue(service.get(r.id(),"world-a").structureChanged()); assertEquals(2,service.get(r.id(),"world-a").total()); }
    @Test void inventoryAssociationRejectsUntrustedWrongOrUnconfirmed() { assertTrue(InventoryAssociation.accepts(single(),single(),27,true,true,5)); assertFalse(InventoryAssociation.accepts(single(),pair(),54,true,true,5)); assertFalse(InventoryAssociation.accepts(single(),single(),27,false,true,5)); assertFalse(InventoryAssociation.accepts(single(),single(),27,true,false,5)); assertFalse(InventoryAssociation.accepts(single(),single(),27,true,true,101)); }
    @Test void rejectsMalformedItemsAndGeometry() { assertThrows(IllegalArgumentException.class,() -> new ItemRecord(54,"minecraft:diamond",1)); assertThrows(IllegalArgumentException.class,() -> new ItemRecord(0,"bad item",1)); assertThrows(IllegalArgumentException.class,() -> new ChestIdentity("world-a","minecraft:overworld",List.of(a,new Position(3,64,0)))); }
}
