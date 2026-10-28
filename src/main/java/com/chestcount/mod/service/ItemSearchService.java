package com.chestcount.mod.service;

import com.chestcount.mod.model.*;
import java.util.*;

public final class ItemSearchService {
    public record Match(ChestRecord chest, long count) {}
    public static List<Match> find(List<ChestRecord> records, String itemId) {
        return records.stream().filter(r -> r.syncedAt() != null).map(r -> new Match(r,
            r.items().stream().filter(i -> i.item_id().equals(itemId)).mapToLong(ItemRecord::count).sum()))
            .filter(m -> m.count() > 0).toList();
    }
}
