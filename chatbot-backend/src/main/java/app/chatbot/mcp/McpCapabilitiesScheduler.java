package app.chatbot.mcp;

import java.time.Duration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

/**
 * Background Scheduler für periodisches Synchronisieren von MCP Server Capabilities.
 * Synchronisiert alle CONNECTED Server alle 5 Minuten.
 * 
 * Kann via application.properties deaktiviert werden:
 * mcp.scheduler.enabled=false
 */
@Component
@ConditionalOnProperty(name = "mcp.scheduler.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class McpCapabilitiesScheduler {

    private final McpServerRepository repository;
    private final McpServerService serverService;

    /**
     * Synchronisiert alle CONNECTED Server alle 5 Minuten.
     * Läuft nach Anwendungsstart mit initialDelay von 60 Sekunden.
     * 
     * WICHTIG: Verwendet concatMap() statt flatMap() für SEQUENTIELLE Verarbeitung!
     * Das verhindert Database Lock Timeouts bei parallelen Updates.
     */
    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000) // 5 Min delay, 1 Min initial delay
    public void syncAllConnectedServers() {
        log.info("Starting scheduled sync of all connected MCP servers");
        
        var connectedServers = repository.findAll().stream()
                .filter(server -> server.getStatus() == McpServerStatus.CONNECTED)
                .toList();

        if (connectedServers.isEmpty()) {
            log.debug("No connected MCP servers found, skipping scheduled sync");
            return;
        }

        log.info("Syncing capabilities for {} connected server(s) SEQUENTIALLY", connectedServers.size());

        Flux.fromIterable(connectedServers)
                .concatMap(server -> serverService.syncCapabilitiesAsync(server.getServerId())
                        .doOnSuccess(synced -> log.debug("Successfully synced server {} in scheduled task", 
                            server.getServerId()))
                        .onErrorResume(error -> {
                            log.error("Failed to sync server {} in scheduled task: {}", 
                                server.getServerId(), error.getMessage());
                            return reactor.core.publisher.Mono.empty();
                        })
                        .timeout(Duration.ofSeconds(30)) // Timeout pro Server
                )
                .collectList()
                .block(Duration.ofMinutes(10)); // Max 10 Min für alle Server

        log.info("Completed scheduled sync of all connected MCP servers");
    }
}
