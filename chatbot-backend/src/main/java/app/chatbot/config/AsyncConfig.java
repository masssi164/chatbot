package app.chatbot.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Konfiguration für asynchrone Task-Ausführung.
 * 
 * Thread Pool Dimensionierung:
 * - MCP Connection Timeout: ~15 Sekunden
 * - Steady State Durchsatz: 10 Threads / 15s = 0.67 Connections/s
 * - Burst Kapazität: (20 Threads + 500 Queue) / 15s = 34.6 Connections/s
 * 
 * Rejection Policy: CallerRunsPolicy
 * - Bei Queue-Overflow führt der aufrufende Thread die Task aus
 * - Verhindert Task-Loss und bietet natürliches Backpressure
 * - Alternative zu AbortPolicy (würde Exception werfen)
 */
@Configuration
public class AsyncConfig {

    /**
     * Thread Pool für asynchrone MCP Server-Verbindungen.
     * 
     * WICHTIG: Diese Werte wurden erhöht um Thread Pool Exhaustion zu vermeiden.
     * Vorher: 2/5/100 → Führte zu RejectedExecutionException bei hoher Last
     * Jetzt:  10/20/500 → Kann ~35 Connections/s im Burst verarbeiten
     * 
     * @return Konfigurierter ThreadPoolTaskExecutor
     */
    @Bean(name = "mcpServerTaskExecutor")
    public Executor mcpServerTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        
        // Core Pool: Immer aktive Threads (auch bei Idle)
        executor.setCorePoolSize(10);
        
        // Max Pool: Maximale Threads bei hoher Last
        executor.setMaxPoolSize(20);
        
        // Queue Capacity: Wartende Tasks bevor Rejection
        executor.setQueueCapacity(500);
        
        executor.setThreadNamePrefix("mcp-async-");
        
        // KRITISCH: CallerRunsPolicy statt Default AbortPolicy
        // Verhindert RejectedExecutionException und bietet Backpressure
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        
        // Graceful Shutdown: Warte auf laufende Tasks
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        
        executor.initialize();
        return executor;
    }
}
