package com.chestcount.mod.service;

import com.chestcount.mod.ChestCountClient;
import com.chestcount.mod.interaction.ChestLocator;
import com.chestcount.mod.model.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.*;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import java.util.*;

/** Only packet-confirmed slots are stored. Never samples the predicted UI inventory. */
public final class InventorySyncService {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("chestcount");
    private final ChestCountClient app;
    private ChestIdentity pending, active;
    private BlockPos clicked;
    private String recordId;
    private long clickTick;
    private int container = -1;
    private List<ItemStack> confirmed;
    private boolean warned;
    private boolean verified;
    private long sequence;
    public InventorySyncService(ChestCountClient app) { this.app = app; }
    public void clicked(ChestIdentity id, BlockPos pos) {
        reset();
        if (app.registry().exact(id).isPresent()) { pending = id; clicked = pos.immutable(); clickTick = app.ticks(); }
    }
    public void cancelPending() { pending = null; }
    public void reset() { sequence++; pending = null; active = null; container = -1; confirmed = null; recordId = null; verified=false; }
    public void open(ClientboundOpenScreenPacket packet) {
        sequence++; active = null; confirmed = null; container = -1; verified=false;
        var mc = app.mc(); var candidate = pending; pending = null;
        LOG.debug("Opening menu {} title={} candidate={} age={}",packet.getContainerId(),packet.getTitle(),candidate,app.ticks()-clickTick);
        if (candidate == null || mc.player == null || mc.level == null || mc.getConnection() == null || app.ticks()-clickTick > 100) return;
        var observed = ChestLocator.locate(mc,app.world(),clicked);
        if (observed.isEmpty() || !candidate.equals(observed.get()) || !(mc.player.containerMenu instanceof ChestMenu menu) || menu.getRowCount()*9 != candidate.slots()) {
            LOG.warn("Skipped menu {}: chest structure or container type/size does not match",packet.getContainerId()); return;
        }
        // The vanilla title must match the physical block's translated/custom display name.
        if (!(mc.level.getBlockEntity(clicked) instanceof ChestBlockEntity be) || !packet.getTitle().getString().equals(
                be.hasCustomName() ? be.getDisplayName().getString() : net.minecraft.network.chat.Component.translatable(candidate.slots() == 54 ? "container.chestDouble" : "container.chest").getString())) {
            LOG.warn("Skipped menu {}: title does not match the physical chest",packet.getContainerId()); return;
        }
        boolean trusted = mc.hasSingleplayerServer() || "vanilla".equals(mc.getConnection().serverBrand())
            || app.config().trustedServers.contains(app.world().split("\\|",2)[0]);
        if (!trusted) { if (!warned) { app.message("unsafe_menu"); warned = true; } return; }
        var r = app.registry().exact(candidate);
        LOG.debug("Trust={} record={}",trusted,r.map(ChestRecord::id));
        if (r.isEmpty()) return;
        active = candidate; recordId = r.get().id(); container = packet.getContainerId();
        verified=!mc.hasSingleplayerServer();
        if (!verified) {
            var playerId=mc.player.getUUID(); var expectedContainer=container; var token=sequence;
            var integrated=mc.getSingleplayerServer();
            integrated.execute(() -> {
                var player=integrated.getPlayerList().getPlayer(playerId);
                boolean physical=player != null && player.containerMenu.containerId==expectedContainer
                    && player.level().dimension().identifier().toString().equals(candidate.dimension())
                    && player.containerMenu instanceof ChestMenu;
                if (physical) {
                    var inventory=((ChestMenu)player.containerMenu).getContainer();
                    physical=inventory.getContainerSize()==candidate.slots();
                    for (var pos:candidate.blocks()) {
                        var block=player.level().getBlockEntity(ChestLocator.block(pos));
                        if (!(block instanceof ChestBlockEntity chest) || !(inventory==chest
                            || inventory instanceof net.minecraft.world.CompoundContainer compound && compound.contains(chest))) physical=false;
                    }
                }
                boolean accepted=physical;
                mc.execute(() -> {
                    if (sequence!=token || container!=expectedContainer || !candidate.equals(active)) return;
                    if (!accepted) { LOG.warn("Skipped menu {}: integrated server inventory does not match chest blocks",expectedContainer); reset(); return; }
                    verified=true; if(confirmed!=null && valid()) save();
                });
            });
        }
    }
    private boolean valid() {
        var mc = app.mc();
        if (active == null || mc.player == null || mc.level == null || !active.world().equals(app.world())
            || mc.player.containerMenu.containerId != container || !(mc.player.containerMenu instanceof ChestMenu)) return false;
        var observed = ChestLocator.locate(mc,app.world(),clicked);
        if (observed.isEmpty() || !observed.get().equals(active)) { reset(); return false; }
        return app.registry().exact(active).isPresent();
    }
    public void content(ClientboundContainerSetContentPacket packet) {
        LOG.debug("Content menu {} expected={} items={} active={}",packet.containerId(),container,packet.items().size(),active);
        if (packet.containerId() != container || !valid() || packet.items().size() != active.slots()+36) return;
        confirmed = new ArrayList<>();
        for (int i=0;i<active.slots();i++) confirmed.add(packet.items().get(i).copy());
        save();
    }
    public void slot(ClientboundContainerSetSlotPacket packet) {
        if (packet.getContainerId() != container || confirmed == null || !valid() || packet.getSlot()<0 || packet.getSlot()>=active.slots()) return;
        confirmed.set(packet.getSlot(),packet.getItem().copy()); save();
    }
    private void save() {
        if (confirmed==null || !InventoryAssociation.accepts(active,ChestLocator.locate(app.mc(),app.world(),clicked).orElse(null),confirmed.size(),true,verified,0)) return;
        var items = new ArrayList<ItemRecord>();
        for (int i=0;i<confirmed.size();i++) {
            var stack = confirmed.get(i);
            if (!stack.isEmpty()) items.add(new ItemRecord(i,BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),stack.getCount()));
        }
        app.registry().snapshot(recordId,active,items);
    }
    public void tick() {
        if (pending != null && app.ticks()-clickTick > 100) pending = null;
        if (active != null && !valid()) reset();
    }
}
