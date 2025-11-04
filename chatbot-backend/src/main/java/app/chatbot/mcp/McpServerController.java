package app.chatbot.mcp;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import app.chatbot.mcp.dto.McpConnectionStatusDto;
import app.chatbot.mcp.dto.McpServerDto;
import app.chatbot.mcp.dto.McpServerRequest;
import app.chatbot.mcp.dto.SyncStatusDto;
import app.chatbot.mcp.events.McpServerUpdatedEvent;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * REST Controller für MCP Server Management.
 * 
 * Event-Driven Architecture Pattern:
 * - POST/PUT → Synchrones DB-Update (schnell, <100ms)
 * - Event published → Async Connection (15 Sekunden, läuft im Background)
 * - Response kommt sofort zurück
 * 
 * Request Deduplication:
 * - Frontend sendet manchmal mehrere parallele Requests
 * - Lock pro serverId verhindert parallele update() Aufrufe
 * - Nur der erste Request führt Update durch, andere warten
 */
@RestController
@RequestMapping("/api/mcp-servers")
@RequiredArgsConstructor
@Slf4j
public class McpServerController {

    private final McpServerService service;
    private final ApplicationEventPublisher eventPublisher;
    
    // Lock per serverId to prevent parallel updates from frontend
    private final ConcurrentHashMap<String, Lock> serverLocks = new ConcurrentHashMap<>();

    @GetMapping
    public Flux<McpServerDto> listServers() {
        log.debug("Listing MCP servers");
        return service.listServers();
    }

    @GetMapping("/{serverId}")
    public Mono<McpServerDto> getServer(@PathVariable("serverId") String serverId) {
        log.debug("Fetching MCP server {}", serverId);
        return service.getServer(serverId);
    }

    @PostMapping
    @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED) // 202 Accepted
    public Mono<McpServerDto> createOrUpdate(@Valid @RequestBody McpServerRequest request) {
        log.info("📥 POST /api/mcp-servers - Received request: serverId={}, name={}, baseUrl={}, transport={}", 
                 request.serverId(), request.name(), request.baseUrl(), request.transport());
        
        String serverId = request.serverId() != null ? request.serverId() : "new-server";
        
        // Deduplizierung: Lock holen für diesen serverId
        Lock lock = serverLocks.computeIfAbsent(serverId, k -> new ReentrantLock());
        
        return Mono.fromCallable(() -> {
            lock.lock();
            log.debug("🔒 Lock acquired for serverId: {}", serverId);
            return lock;
        })
        .flatMap(acquiredLock -> {
            log.debug("Creating or updating MCP server {} (locked)", serverId);
            
            // 1. Synchrones DB-Update (schnell, <100ms)
            return service.createOrUpdate(request)
                    .doOnNext(dto -> {
                        // 2. Event publishen → Async Connection läuft im Background
                        log.info("📤 Publishing McpServerUpdatedEvent for server {}", dto.serverId());
                        eventPublisher.publishEvent(new McpServerUpdatedEvent(dto.serverId()));
                    })
                    .doFinally(signal -> {
                        acquiredLock.unlock();
                        log.debug("🔓 Lock released for serverId: {}", serverId);
                    });
        })
        .doOnError(error -> log.error("❌ Error creating/updating MCP server: {}", error.getMessage(), error));
    }

    @PutMapping("/{serverId}")
    @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED) // 202 Accepted
    public Mono<McpServerDto> update(@PathVariable("serverId") String serverId,
                               @Valid @RequestBody McpServerRequest request) {
        // Deduplizierung: Lock holen für diesen serverId
        Lock lock = serverLocks.computeIfAbsent(serverId, k -> new ReentrantLock());
        
        return Mono.fromCallable(() -> {
            lock.lock();
            return lock;
        })
        .flatMap(acquiredLock -> {
            log.debug("Updating MCP server {} (locked)", serverId);
            
            // 1. Synchrones DB-Update (schnell, <100ms)
            return service.update(serverId, request)
                    .doOnNext(dto -> {
                        // 2. Event publishen → Async Connection läuft im Background
                        log.info("Publishing McpServerUpdatedEvent for server {}", serverId);
                        eventPublisher.publishEvent(new McpServerUpdatedEvent(serverId));
                    })
                    .doFinally(signal -> acquiredLock.unlock());
        });
    }

    @DeleteMapping("/{serverId}")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable("serverId") String serverId) {
        log.debug("Deleting MCP server {}", serverId);
        return service.delete(serverId);
    }

    @PostMapping("/{serverId}/verify")
    public Mono<McpConnectionStatusDto> verifyConnection(@PathVariable("serverId") String serverId) {
        log.debug("Verifying connection to MCP server {}", serverId);
        return service.verifyConnectionAsync(serverId)
                .doOnError(ex -> {
                    if (ex instanceof ResponseStatusException rse) {
                        log.warn("Verify connection request for server {} failed with status {} and reason {}",
                                serverId, rse.getStatusCode(), rse.getReason(), rse);
                    } else {
                        log.error("Unexpected error during verify request for server {}", serverId, ex);
                    }
                });
    }

    @PostMapping("/{serverId}/sync")
    public Mono<SyncStatusDto> syncCapabilities(@PathVariable("serverId") String serverId) {
        log.debug("Triggering capabilities sync for MCP server {}", serverId);
        return service.sync(serverId);
    }
}
