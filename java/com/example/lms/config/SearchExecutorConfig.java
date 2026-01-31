package com.example.lms.config;

import com.example.lms.infra.exec.ContextAwareExecutorService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Configuration
public class SearchExecutorConfig {


    @Value("${search.executor.io.core-size:4}")
    private int searchIoCoreSize;

    @Value("${search.executor.io.max-size:64}")
    private int searchIoMaxSize;

    @Value("${search.executor.io.queue-capacity:256}")
    private int searchIoQueueCapacity;

    @Value("${search.executor.io.keep-alive-seconds:60}")
    private long searchIoKeepAliveSeconds;

    /**
     * Executor for "fast" LLM utilities (query transform, analysis, etc.).
     *
     * <p>
     * Why bounded?
     * - A fixed thread pool created via Executors.newFixedThreadPool uses an
     * unbounded queue.
     * - Under sustained timeouts/interruption, that queue can grow without bound
     * (latency + memory blowups).
     *
     * <p>
     * We prefer a bounded queue + fail-fast rejection so upstream can fall back.
     */
    @Bean(name = "llmFastExecutor", destroyMethod = "shutdown")
    public ExecutorService llmFastExecutor() {
        int nThreads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        int queueCap = 64;

        ExecutorService delegate = new ThreadPoolExecutor(
                nThreads,
                nThreads,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCap),
                r -> {
                    Thread t = new Thread(r, "llm-fast-" + System.nanoTime());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy()) {
            @Override
            protected void beforeExecute(Thread t, Runnable r) {
                if (t.isInterrupted()) {
                    Thread.interrupted(); // clear poisoned interrupt flag
                }
                super.beforeExecute(t, r);
            }
        };

        return new ContextAwareExecutorService(delegate);
    }

    /**
     * Executor for CPU-heavy search operations.
     */
    @Bean(name = "searchExecutor", destroyMethod = "shutdown")
    public ExecutorService searchExecutor() {
        ExecutorService delegate = java.util.concurrent.Executors.newFixedThreadPool(
                Math.max(2, Runtime.getRuntime().availableProcessors()),
                r -> {
                    Thread t = new Thread(r);
                    t.setName("search-exec-" + t.getId());
                    t.setDaemon(true);
                    return t;
                });
        return new ContextAwareExecutorService(delegate);
    }

    /**
     * Executor for search I/O operations (HybridWebSearchProvider 등에서 사용).
     * CachedThreadPool을 사용하여 유동적으로 스레드를 생성하고,
     * 디버깅 편의를 위해 스레드 이름 패턴(search-io-N)을 지정.
     */
    
    @Bean(name = "searchIoExecutor", destroyMethod = "shutdown")
    public ExecutorService searchIoExecutor() {
        java.util.concurrent.atomic.AtomicInteger counter = new java.util.concurrent.atomic.AtomicInteger(0);

        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "search-io-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };

        int cap = Math.max(0, searchIoQueueCapacity);
        // 큐를 사용하는 경우 core=0이면 작업이 큐에만 쌓이고 실행 스레드가 생성되지 않을 수 있다.
        int core = Math.max((cap == 0 ? 0 : 1), Math.max(0, searchIoCoreSize));
        int max = Math.max(core + 1, Math.max(core, searchIoMaxSize));
        long keepAlive = Math.max(0L, searchIoKeepAliveSeconds);

        BlockingQueue<Runnable> q = (cap == 0) ? new SynchronousQueue<>() : new ArrayBlockingQueue<>(cap);

        ThreadPoolExecutor ex = new ThreadPoolExecutor(
                core,
                max,
                keepAlive,
                TimeUnit.SECONDS,
                q,
                tf,
                new ThreadPoolExecutor.CallerRunsPolicy()
        ) {
            @Override
            protected void beforeExecute(Thread t, Runnable r) {
                // Thread interrupt leak 방지 (취소/타임아웃 후 재사용 시)
                if (t.isInterrupted()) {
                    Thread.interrupted(); // clear poisoned interrupt
                }
                super.beforeExecute(t, r);
            }
        };

        ex.allowCoreThreadTimeOut(true);
        return new ContextAwareExecutorService(ex);
    }

}
