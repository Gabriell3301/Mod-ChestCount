package com.chestcount.mod.rendering;

import com.chestcount.mod.ChestCountClient;
import com.chestcount.mod.interaction.ChestLocator;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.gizmos.*;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import java.util.*;

public final class ChestOutlineRenderer {
    public static void register(ChestCountClient app) {
        var boxes = new ArrayList<AABB>();
        String[] dimension={null};
        long[] revision={-1};
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            var mc = app.mc();
            if (!app.ready() || mc.player == null || mc.level == null || app.world() == null) { boxes.clear(); return; }
            var currentDimension=mc.level.dimension().identifier().toString();
            if (app.ticks()%10 != 0 && currentDimension.equals(dimension[0]) && revision[0]==app.registry().revision()) return;
            dimension[0]=currentDimension; revision[0]=app.registry().revision();
            boxes.clear();
                int rendered = 0;
                for (var r : app.registry().nearby(app.world(),mc.level.dimension().identifier().toString(),mc.player.getX(),mc.player.getY(),mc.player.getZ(),app.config().outlineRadius)) {
                    if (rendered >= 256 || r.structureChanged()) continue;
                    var blocks = r.identity().blocks(); var first = ChestLocator.block(blocks.getFirst());
                    if (!ChestLocator.locate(mc,app.world(),first).filter(r.identity()::equals).isPresent()) continue;
                    var last = ChestLocator.block(blocks.getLast());
                    var box = new AABB(Math.min(first.getX(),last.getX())+.055,first.getY()+.01,Math.min(first.getZ(),last.getZ())+.055,
                        Math.max(first.getX(),last.getX())+.945,first.getY()+.885,Math.max(first.getZ(),last.getZ())+.945);
                    // Default gizmos use depth testing. Never setAlwaysOnTop().
                    boxes.add(box); rendered++;
                }
        });
        LevelExtractionEvents.END_EXTRACTION.register(context -> {
            var mc=app.mc();
            if (!app.ready() || app.world()==null || mc.player==null || mc.level==null || !mc.level.dimension().identifier().toString().equals(dimension[0]) || revision[0]!=app.registry().revision()) return;
            if (!app.config().alwaysShowOutlines && !mc.player.getMainHandItem().is(Items.FEATHER) && !mc.player.getOffhandItem().is(Items.FEATHER)) return;
            try (var collection=mc.levelExtractor.collectPerFrameMainThreadGizmos()) {
                for(var box:boxes) {
                    var min=net.minecraft.core.BlockPos.containing(box.minX,box.minY,box.minZ);
                    var max=net.minecraft.core.BlockPos.containing(box.maxX,box.maxY,box.maxZ);
                    if(mc.level.hasChunkAt(min) && mc.level.hasChunkAt(max)) Gizmos.cuboid(box,GizmoStyle.stroke(0xB34A90E2,1.5f));
                }
            }
        });
    }
}
