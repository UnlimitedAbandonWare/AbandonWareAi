package com.example.lms.manifest;

import java.util.HashMap;
import java.util.Map;



public class ModelRegistry {
    private final ModelsManifest mf;
    private final Map<String,String> idByAlias = new HashMap<>();

    public ModelRegistry(ModelsManifest mf) {
        this.mf = mf;
        if (mf.getAliases() != null) {
            idByAlias.putAll(mf.getAliases());
        }
        if (mf.getBindings() != null) {
            if (mf.getBindings().getDefault() != null) idByAlias.put("default", mf.getBindings().getDefault());
            if (mf.getBindings().getMoe() != null) idByAlias.put("moe", mf.getBindings().getMoe());
        }
    }

    public String resolve(String aliasOrId) {
        if (aliasOrId == null) return null;
        return idByAlias.getOrDefault(aliasOrId, aliasOrId);
    }

    public String defaultId() { return resolve("default"); }
    public String moeId()     { return resolve("moe"); }
}