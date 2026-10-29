package com.chestcount.mod;

import com.chestcount.mod.commands.ChestCountCommands;
import com.chestcount.mod.model.*;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PresentationTest {
    JsonObject language(String name) throws Exception { return JsonParser.parseString(Files.readString(Path.of("src/main/resources/assets/chestcount/lang/"+name+".json"))).getAsJsonObject(); }
    @Test void bothLanguagesHaveMatchingKeysAndPlaceholders() throws Exception {
        var pt=language("pt_pt"); var en=language("en_us"); assertEquals(pt.keySet(),en.keySet());
        for(var key:pt.keySet()) assertEquals(pt.get(key).getAsString().split("%s",-1).length,en.get(key).getAsString().split("%s",-1).length,key);
        assertEquals("%s baús · %s itens · %s tipos",pt.get("chestcount.count").getAsString());
    }
    @Test void quotedNamesRoundTripIncludingEscapes() {
        for(var name:List.of("Baú de Minérios","A \"quote\" \\ B")) assertEquals(name,ChestCountCommands.name(ChestCountCommands.quote(name)));
        assertEquals("Baú de Minérios",ChestCountCommands.name("Baú de Minérios"));
        assertThrows(IllegalArgumentException.class,() -> ChestCountCommands.name("\"Bad\" trailing"));
    }
    @Test void positionIsCompactWithoutInternalId() {
        var r=new ChestRecord(UUID.randomUUID().toString(),new ChestIdentity("w","minecraft:overworld",List.of(new Position(120,64,-30))),"Minerios",Instant.now().toString(),null,List.of(),false);
        assertEquals("[120, 64, -30]",ChestCountCommands.position(r));
        assertFalse(ChestCountCommands.position(r).contains(r.id()));
    }
}
