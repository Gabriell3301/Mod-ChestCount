package com.chestcount.mod.service;

import com.chestcount.mod.model.ChestIdentity;

/** Pure policy. A generic server chest menu is not proof of a physical inventory. */
public final class InventoryAssociation {
    public static boolean accepts(ChestIdentity expected, ChestIdentity observed, int slots,
                                  boolean fullPacket, boolean verifiedPhysicalInventory, long ageTicks) {
        return expected != null && expected.equals(observed) && slots == expected.slots()
            && fullPacket && verifiedPhysicalInventory && ageTicks >= 0 && ageTicks <= 100;
    }
}
