package com.example.lms.service.rag.langgraph;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rag.langgraph")
public class RagGraphProperties {

    public enum Mode {
        OFF,
        SHADOW,
        PRIMARY
    }

    private Mode mode = Mode.OFF;
    private int maxSteps = 12;
    private long timeoutMs = 15_000L;
    private String checkpoint = "memory";
    private Postgres postgres = new Postgres();

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode == null ? Mode.OFF : mode;
    }

    public int getMaxSteps() {
        return maxSteps;
    }

    public void setMaxSteps(int maxSteps) {
        this.maxSteps = Math.max(4, maxSteps);
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = Math.max(0L, timeoutMs);
    }

    public String getCheckpoint() {
        return checkpoint;
    }

    public void setCheckpoint(String checkpoint) {
        this.checkpoint = (checkpoint == null || checkpoint.isBlank()) ? "memory" : checkpoint;
    }

    public Postgres getPostgres() {
        return postgres;
    }

    public void setPostgres(Postgres postgres) {
        this.postgres = postgres == null ? new Postgres() : postgres;
    }

    public boolean isOff() {
        return mode == Mode.OFF;
    }

    public boolean isShadow() {
        return mode == Mode.SHADOW;
    }

    public boolean isPrimary() {
        return mode == Mode.PRIMARY;
    }

    public static class Postgres {
        private String host = "localhost";
        private int port = 5432;
        private String database = "lg4j_store";
        private String user = "lg4j";
        private String password = "";
        private boolean createTables = false;
        private boolean dropTablesFirst = false;

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = (host == null || host.isBlank()) ? "localhost" : host.trim();
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port <= 0 ? 5432 : port;
        }

        public String getDatabase() {
            return database;
        }

        public void setDatabase(String database) {
            this.database = (database == null || database.isBlank()) ? "lg4j_store" : database.trim();
        }

        public String getUser() {
            return user;
        }

        public void setUser(String user) {
            this.user = (user == null || user.isBlank()) ? "lg4j" : user.trim();
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password == null ? "" : password;
        }

        public boolean isCreateTables() {
            return createTables;
        }

        public void setCreateTables(boolean createTables) {
            this.createTables = createTables;
        }

        public boolean isDropTablesFirst() {
            return dropTablesFirst;
        }

        public void setDropTablesFirst(boolean dropTablesFirst) {
            this.dropTablesFirst = dropTablesFirst;
        }
    }
}
