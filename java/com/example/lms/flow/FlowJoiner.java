
package com.example.lms.flow;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;



public class FlowJoiner {
    private final ExecutorService pool = Executors.newCachedThreadPool();

    public static class Health {
        public boolean P=true,R=true,C=true,Y=true,K=true;
    }

    public <T> CompletableFuture<T> fallback(CompletableFuture<T> primary, CompletableFuture<T> secondary) {
        return primary.exceptionallyCompose(ex -> secondary);
    }
}