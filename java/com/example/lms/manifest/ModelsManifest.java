package com.example.lms.manifest;

import java.util.List;
import java.util.Map;



public class ModelsManifest {
    private String version;
    private Bindings bindings;
    private List<Model> models;
    private Map<String, String> aliases;
    // AUTO-PATCH: defensive fields to tolerate extra keys in YAML
    private java.util.Map<String, Object> probe;
    private java.util.Map<String, Object> retrieval;

    public static class Bindings {
        private String _default;
        private String moe;
        public String getDefault() { return _default; }
        public void setDefault(String v) { this._default = v; }
        public String getMoe() { return moe; }
        public void setMoe(String v) { this.moe = v; }
    }

    public static class Model {
        private String id;
        private String provider;
        private Endpoint endpoint;
        private List<String> capabilities;
        private Integer ctx;
        private Map<String, Object> price;
        private List<String> tags;
        public String getId() { return id; }
        public void setId(String v) { this.id = v; }
        public String getProvider() { return provider; }
        public void setProvider(String v) { this.provider = v; }
        public Endpoint getEndpoint() { return endpoint; }
        public void setEndpoint(Endpoint e) { this.endpoint = e; }
        public List<String> getCapabilities() { return capabilities; }
        public void setCapabilities(List<String> c) { this.capabilities = c; }
        public Integer getCtx() { return ctx; }
        public void setCtx(Integer c) { this.ctx = c; }
        public Map<String, Object> getPrice() { return price; }
        public void setPrice(Map<String, Object> p) { this.price = p; }
        public List<String> getTags() { return tags; }
        public void setTags(List<String> t) { this.tags = t; }
    }

    public static class Endpoint {
        public String type;
        public String base_url;
        public String key_env;
    }

    public String getVersion() { return version; }
    public void setVersion(String v) { this.version = v; }
    public Bindings getBindings() { return bindings; }
    public void setBindings(Bindings b) { this.bindings = b; }
    public List<Model> getModels() { return models; }
    public void setModels(List<Model> m) { this.models = m; }
    public Map<String, String> getAliases() { return aliases; }
    public void setAliases(Map<String, String> a) { this.aliases = a; }

    // AUTO-PATCH: getters/setters for defensive fields
    public java.util.Map<String, Object> getProbe() { return probe; }
    public void setProbe(java.util.Map<String, Object> p) { this.probe = p; }
    public java.util.Map<String, Object> getRetrieval() { return retrieval; }
    public void setRetrieval(java.util.Map<String, Object> r) { this.retrieval = r; }
}