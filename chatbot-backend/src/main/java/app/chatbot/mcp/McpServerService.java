package app.chatbot.mcp;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.springframework.http.HttpStatus.NOT_FOUND;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import app.chatbot.mcp.dto.McpConnectionStatusDto;
import app.chatbot.mcp.dto.McpServerDto;
import app.chatbot.mcp.dto.McpServerRequest;
import app.chatbot.mcp.events.McpServerStatusPublisher;
import app.chatbot.security.EncryptionException;
import app.chatbot.security.SecretEncryptor;
import app.chatbot.utils.GenericMapper;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
@Slf4j
public class McpServerService {

    private final McpServerRepository repository;
    private final GenericMapper mapper;
    private final SecretEncryptor secretEncryptor;
    private final McpClientService mcpClientService;
    private final McpSessionRegistry sessionRegistry;
    private final ObjectMapper objectMapper;
    private final McpServerStatusPublisher statusPublisher;

    public Flux<McpServerDto> listServers() {
        return repository.findAll()
                .sort((a, b) -> b.getLastUpdated().compareTo(a.getLastUpdated()))
                .map(this::toDto);
    }

    public Mono<McpServerDto> getServer(String serverId) {
        return repository.findByServerId(serverId)
                .map(this::toDto)
                .switchIfEmpty(Mono.error(new ResponseStatusException(NOT_FOUND, "MCP server not found")));
    }

    public Mono<McpServerDto> createOrUpdate(McpServerRequest request) {
        log.info("Creating/updating MCP server: name={}, baseUrl={}, transport={}", 
                 request.name(), request.baseUrl(), request.transport());
        
        String requestedId = StringUtils.hasText(request.serverId()) ? request.serverId().trim() : null;

        Mono<McpServer> serverMono = requestedId != null
                ? repository.findByServerId(requestedId).defaultIfEmpty(new McpServer())
                : Mono.just(new McpServer());

        return serverMono.flatMap(server -> {
            if (server.getServerId() == null) {
                String newId = requestedId != null ? requestedId : generateServerId();
                server.setServerId(newId);
                log.info("Assigning new serverId: {}", newId);
            }
            applyUpdates(server, request);
            return repository.save(server);
        }).map(saved -> {
            log.info("Successfully saved MCP server: serverId={}, status={}", 
                     saved.getServerId(), saved.getStatusEnum());
            return toDto(saved);
        });
    }

    public Mono<McpServerDto> update(String serverId, McpServerRequest request) {
        // Event-Driven: Optimistic Locking ist OK, da schnell (<100ms)
        // Connection läuft async via Event → Keine Race Conditions
        return repository.findByServerId(serverId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(NOT_FOUND, "MCP server not found")))
                .flatMap(server -> {
                    applyUpdates(server, request);
                    return repository.save(server);
                })
                .map(this::toDto);
    }

    public Mono<Void> deleteServer(String serverId) {
        return repository.findByServerId(serverId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(NOT_FOUND, "MCP server not found")))
                .flatMap(server -> {
                    mcpClientService.closeConnection(server.getServerId());
                    return repository.delete(server);
                });
    }

    public Mono<Void> delete(String serverId) {
        mcpClientService.closeConnection(serverId);
        return repository.deleteByServerId(serverId);
    }

    private void applyUpdates(McpServer server, McpServerRequest request) {
        server.setName(request.name().trim());
        server.setBaseUrl(request.baseUrl().trim());

        // Encrypt API key before storing
        String apiKey = request.apiKey();
        if (StringUtils.hasText(apiKey)) {
            try {
                String encrypted = secretEncryptor.encrypt(apiKey.trim());
                server.setApiKey(encrypted);
            } catch (EncryptionException ex) {
                log.error("Failed to encrypt API key for MCP server {}", server.getServerId(), ex);
                throw new IllegalStateException("Failed to encrypt API key", ex);
            }
        } else {
            server.setApiKey(null);
        }

        if (request.status() != null) {
            server.setStatusEnum(request.status());
        }
        
        if (request.transport() != null) {
            server.setTransportEnum(request.transport());
        }
        
        server.setLastUpdated(Instant.now());
    }

    private McpServerDto toDto(McpServer server) {
        // Check if API key exists (don't decrypt for DTO)
        boolean hasApiKey = StringUtils.hasText(server.getApiKey());
        
        return new McpServerDto(
                server.getServerId(),
                server.getName(),
                server.getBaseUrl(),
                hasApiKey,
                server.getStatusEnum(),
                server.getTransportEnum(),
                server.getLastUpdated()
        );
    }

    public McpConnectionStatusDto verifyConnection(String serverId) {
        return verifyConnectionAsync(serverId)
            .onErrorResume(error -> {
                if (error instanceof ResponseStatusException responseStatusException
                        && responseStatusException.getStatusCode().equals(NOT_FOUND)) {
                    return Mono.error(responseStatusException);
                }

                log.error("Verify connection failed for server {}", serverId, error);
                String message = error.getMessage() != null
                        ? error.getMessage()
                        : error.getClass().getSimpleName();
                return Mono.just(new McpConnectionStatusDto(
                        McpServerStatus.ERROR,
                        0,
                        "Connection failed: " + message));
            })
            .blockOptional(Duration.ofSeconds(15))
            .orElseGet(() -> {
                log.warn("Verify connection for server {} timed out after 15 seconds", serverId);
                return new McpConnectionStatusDto(
                        McpServerStatus.ERROR,
                        0,
                        "Connection verification timed out after 15s");
            });
    }

    // ===== Reactive Methods (Async with Project Reactor) =====

    /**
     * Verifiziert MCP-Server-Verbindung asynchron (non-blocking).
     * Verwendet McpSessionRegistry für Session-Management und Optimistic Locking statt Pessimistic.
     * 
     * @param serverId Die Server-ID
     * @return Mono mit Connection-Status DTO
     */
    public Mono<McpConnectionStatusDto> verifyConnectionAsync(String serverId) {
        return repository.findByServerId(serverId)
            .switchIfEmpty(Mono.error(new ResponseStatusException(NOT_FOUND, 
                "MCP server not found: " + serverId)))
            .flatMap(server -> {
                // Decrypt API key
                String decryptedApiKey = null;
                if (StringUtils.hasText(server.getApiKey())) {
                    try {
                        decryptedApiKey = secretEncryptor.decrypt(server.getApiKey());
                    } catch (EncryptionException ex) {
                        log.error("Failed to decrypt API key for server {}", serverId, ex);
                        return updateServerStatusAsync(server, McpServerStatus.ERROR)
                            .then(Mono.just(new McpConnectionStatusDto(
                                McpServerStatus.ERROR,
                                0,
                                "Failed to decrypt API key: " + ex.getMessage()
                            )));
                    }
                }

                // Get or create MCP session (non-blocking)
                return sessionRegistry.getOrCreateSession(serverId)
                    .flatMap(client -> client.listTools()
                        .map(result -> result.tools() != null ? result.tools().size() : 0)
                        .map(toolCount -> new McpConnectionStatusDto(
                                McpServerStatus.CONNECTED,
                                toolCount,
                                null
                        ))
                    )
                    .timeout(Duration.ofSeconds(15))
                    .onErrorResume(error -> {
                        if (error instanceof ResponseStatusException responseStatusException
                                && responseStatusException.getStatusCode().equals(NOT_FOUND)) {
                            return Mono.error(responseStatusException);
                        }

                        log.error("Connection test failed for server {}", serverId, error);
                        String message = error.getMessage() != null
                                ? error.getMessage()
                                : error.getClass().getSimpleName();
                        return Mono.just(new McpConnectionStatusDto(
                                McpServerStatus.ERROR,
                                0,
                                "Connection failed: " + message
                        ));
                    });
            });
    }

    /**
     * Synchronisiert Capabilities (Tools/Resources/Prompts) von MCP-Server und cached in DB.
     * Wird aufgerufen bei: 1) Manueller Trigger (POST /sync), 2) Scheduled Task.
     * 
     * @param serverId Die Server-ID
     * @return Mono mit aktualisiertem McpServer
     */
    public Mono<McpServer> syncCapabilitiesAsync(String serverId) {
        log.info("Starting capabilities sync for server {}", serverId);
        
        return repository.findByServerId(serverId)
            .switchIfEmpty(Mono.error(new ResponseStatusException(NOT_FOUND, 
                "MCP server not found: " + serverId)))
            .flatMap(server -> {
                // Update sync status to SYNCING
                return updateServerWithRetry(serverId, s -> s.setSyncStatusEnum(SyncStatus.SYNCING))
                    .then(sessionRegistry.getOrCreateSession(serverId))
                    .flatMap(client -> Mono.zip(
                        client.listTools().onErrorResume(e -> {
                            log.warn("Failed to fetch tools for server {}: {}", serverId, e.getMessage());
                            return Mono.just(new McpSchema.ListToolsResult(List.of(), null));
                        }),
                        client.listResources().onErrorResume(e -> {
                            log.warn("Failed to fetch resources for server {}: {}", serverId, e.getMessage());
                            return Mono.just(new McpSchema.ListResourcesResult(List.of(), null));
                        }),
                        client.listPrompts().onErrorResume(e -> {
                            log.warn("Failed to fetch prompts for server {}: {}", serverId, e.getMessage());
                            return Mono.just(new McpSchema.ListPromptsResult(List.of(), null));
                        })
                    ))
                    .flatMap(tuple -> {
                        try {
                            String toolsJson = objectMapper.writeValueAsString(tuple.getT1().tools());
                            String resourcesJson = objectMapper.writeValueAsString(tuple.getT2().resources());
                            String promptsJson = objectMapper.writeValueAsString(tuple.getT3().prompts());
                            
                            log.debug("Synced capabilities for server {}: {} tools, {} resources, {} prompts",
                                serverId, 
                                tuple.getT1().tools() != null ? tuple.getT1().tools().size() : 0,
                                tuple.getT2().resources() != null ? tuple.getT2().resources().size() : 0,
                                tuple.getT3().prompts() != null ? tuple.getT3().prompts().size() : 0);
                            
                            return updateServerWithRetry(serverId, s -> {
                                s.setToolsCache(toolsJson);
                                s.setResourcesCache(resourcesJson);
                                s.setPromptsCache(promptsJson);
                                s.setLastSyncedAt(Instant.now());
                                s.setSyncStatusEnum(SyncStatus.SYNCED);
                            });
                        } catch (JsonProcessingException ex) {
                            log.error("Failed to serialize capabilities for server {}", serverId, ex);
                            return Mono.error(new IllegalStateException(
                                "Failed to serialize capabilities: " + ex.getMessage(), ex));
                        }
                    })
                    .doOnSuccess(s -> log.info("Successfully synced capabilities for server {}", serverId))
                    .onErrorResume(error -> {
                        log.error("Sync failed for server {}: {}", serverId, error.getMessage());
                        return updateServerWithRetry(serverId, s -> s.setSyncStatusEnum(SyncStatus.SYNC_FAILED))
                            .then(Mono.error(error));
                    });
            })
            .timeout(Duration.ofSeconds(30));
    }

    /**
     * Hilfsmethode: Update Server-Status asynchron mit Optimistic Lock Retry.
     * 
     * @param server Der Server
     * @param newStatus Der neue Status
     * @return Mono das completet wenn Update erfolgreich
     */
    Mono<Void> updateServerStatusAsync(McpServer server, McpServerStatus newStatus) {
        String serverId = server.getServerId();
        if (!StringUtils.hasText(serverId)) {
            return Mono.error(new IllegalStateException("Server ID required for status update"));
        }

        return updateServerWithRetry(serverId, entity -> {
            entity.setStatusEnum(newStatus);
            entity.setLastUpdated(Instant.now());
        })
        .doOnSuccess(updated -> {
            server.setStatusEnum(updated.getStatusEnum());
            server.setLastUpdated(updated.getLastUpdated());
        })
        .then();
    }

    /**
     * Hilfsmethode: Update Server mit Retry bei Optimistic Lock Conflicts.
     * Diese Methode nutzt nun Pessimistic Locking, sodass Retries nicht mehr nötig sind.
     * Event-Driven: Keine Pessimistic Locks mehr nötig!
     * Updates kommen sequenziell via Event → Optimistic Locking reicht.
     * 
     * @param serverId Die Server-ID
     * @param updateFn Consumer-Funktion die das Update durchführt
     * @return Mono mit aktualisiertem Server
     */
    private Mono<McpServer> updateServerWithRetry(String serverId,
                                                   java.util.function.Consumer<McpServer> updateFn) {
        return repository.findByServerId(serverId)
            .switchIfEmpty(Mono.error(new ResponseStatusException(NOT_FOUND,
                "MCP server not found: " + serverId)))
            .flatMap(server -> {
                updateFn.accept(server);
                return repository.save(server);
            })
            .retry(3);
    }

    // ===== Public API Methods (Blocking for REST Controllers) =====

    /**
     * Triggert manuellen Sync (reactive für REST Endpoint).
     * 
     * @param serverId Server ID
     * @return Mono mit Sync Status DTO
     */
    public Mono<app.chatbot.mcp.dto.SyncStatusDto> sync(String serverId) {
        return syncCapabilitiesAsync(serverId)
                .timeout(Duration.ofSeconds(30))
                .map(synced -> new app.chatbot.mcp.dto.SyncStatusDto(
                    synced.getServerId(),
                    synced.getSyncStatusEnum(),
                    synced.getLastSyncedAt(),
                    "Sync completed successfully"
                ))
                .onErrorResume(ex -> {
                    log.error("Manual sync failed for server {}", serverId, ex);
                    
                    // Update status to SYNC_FAILED
                    return repository.findByServerId(serverId)
                            .switchIfEmpty(Mono.error(new ResponseStatusException(NOT_FOUND, "Server not found")))
                            .flatMap(server -> {
                                server.setSyncStatusEnum(SyncStatus.SYNC_FAILED);
                                return repository.save(server);
                            })
                            .map(server -> new app.chatbot.mcp.dto.SyncStatusDto(
                                serverId,
                                SyncStatus.SYNC_FAILED,
                                null,
                                "Sync failed: " + ex.getMessage()
                            ));
                });
    }

    private String generateServerId() {
        return "mcp-" + UUID.randomUUID();
    }

    // ===== Helper Methods - Fully Reactive =====
    // (Werden von McpConnectionService.connectAndSync() verwendet)

    public Mono<McpServer> loadAndUpdateStatus(String serverId, McpServerStatus status, SyncStatus syncStatus) {
        return repository.findByServerId(serverId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(NOT_FOUND, "MCP server not found: " + serverId)))
                .flatMap(server -> {
                    server.setStatusEnum(status);
                    server.setSyncStatusEnum(syncStatus);
                    return repository.save(server);
                });
    }

    public Mono<McpServer> updateServerStatus(String serverId, McpServerStatus status, SyncStatus syncStatus) {
        return repository.findByServerId(serverId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(NOT_FOUND, "MCP server not found: " + serverId)))
                .flatMap(server -> {
                    server.setStatusEnum(status);
                    if (syncStatus != null) {
                        server.setSyncStatusEnum(syncStatus);
                    }
                    return repository.save(server);
                });
    }

    public Mono<McpServer> saveCapabilitiesAndMarkSynced(String serverId, String toolsJson, 
                                                      String resourcesJson, String promptsJson) {
        return repository.findByServerId(serverId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(NOT_FOUND, "MCP server not found: " + serverId)))
                .flatMap(server -> {
                    server.setToolsCache(toolsJson);
                    server.setResourcesCache(resourcesJson);
                    server.setPromptsCache(promptsJson);
                    server.setLastSyncedAt(Instant.now());
                    server.setSyncStatusEnum(SyncStatus.SYNCED);
                    return repository.save(server);
                });
    }

    public Mono<Void> updateServerToError(String serverId, String message) {
        return repository.findByServerId(serverId)
                .flatMap(server -> {
                    server.setStatusEnum(McpServerStatus.ERROR);
                    return repository.save(server).then(Mono.fromRunnable(() ->
                        statusPublisher.publishStatusUpdate(serverId, server.getName(), 
                                McpServerStatus.ERROR, server.getSyncStatusEnum(), message)
                    ));
                })
                .then();
    }

    public Mono<Void> updateServerToSyncFailed(String serverId, String message) {
        return repository.findByServerId(serverId)
                .flatMap(server -> {
                    server.setSyncStatusEnum(SyncStatus.SYNC_FAILED);
                    return repository.save(server).then(Mono.fromRunnable(() ->
                        statusPublisher.publishStatusUpdate(serverId, server.getName(), 
                                server.getStatusEnum(), SyncStatus.SYNC_FAILED, message)
                    ));
                })
                .then();
    }
}

