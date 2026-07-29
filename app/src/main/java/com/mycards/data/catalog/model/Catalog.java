package com.mycards.data.catalog.model;

import java.util.ArrayList;
import java.util.List;

/** Root of the catalog document. */
public class Catalog {

    public int schemaVersion;
    public int catalogVersion;
    public String updatedAt;
    public List<CardTypeDef> cardTypes;

    public List<CardTypeDef> cardTypesOrEmpty() {
        return cardTypes == null ? new ArrayList<CardTypeDef>() : cardTypes;
    }

    public CardTypeDef findById(String id) {
        for (CardTypeDef def : cardTypesOrEmpty()) {
            if (def.id != null && def.id.equals(id)) {
                return def;
            }
        }
        return null;
    }

    /**
     * A catalog is only usable if it declares at least one card type; a newer schema than
     * this build understands is rejected so a future format cannot corrupt existing data.
     */
    public boolean isUsable(int maxSupportedSchema) {
        return schemaVersion <= maxSupportedSchema && !cardTypesOrEmpty().isEmpty();
    }
}
