package app.chatbot.mcp;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.springframework.http.HttpStatus.NOT_FOUND;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import org.springframework.orm.ObjectOptimisticLockingFailureException;
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

    @Transactional(readOnly = true)
    public List<McpServerDto> listServers() {
        return repository.findAll().stream()
                .sorted((a, b) -> b.getLastUpdated().compareTo(a.getLastUpdated()))
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public McpServerDto getServer(String serverId) {
        return repository.findByServerId(serverId)
                .map(this::toDto)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "MCP server not found"));
    }

    @Transactional
    public McpServerDto createOrUpdate(McpServerRequest request) {
        String requestedId = StringUtils.hasText(request.serverId()) ? request.serverId().trim() : null;

        McpServer server = requestedId != null
                ? repository.findByServerId(requestedId).orElse(null)
                : null;

        if (server == null) {
            server = new McpServer();
            server.setServerId(requestedId != null ? requestedId : generateServerId());
        }

        applyUpdates(server, request);
        McpServer saved = repository.save(server);
        // Session stays open - will be closed only on delete or app shutdown
        return toDto(saved);
    }

    @Transactional
    public McpServerDto update(String serverId, McpServerRequest request) {
        // Event-Driven: Optimistic Locking ist OK, da schnell (<100ms)
        // Connection läuft async via Event → Keine Race Conditions
        McpServer server = repository.findByServerId(serverId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "MCP server not found"));

        applyUpdates(server, request);
        McpServer saved = repository.save(server);
        // Session stays open - will be closed by delete() or on app shutdown
        return toDto(saved);
    }

    @Transactional
    public void deleteServer(String serverId) {
        McpServer server = repository.findByServerId(serverId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "MCP server not found"));
        mcpClientService.closeConnection(server.getServerId());
        repository.delete(server);
    }

    @Transactional
    public void delete(String serverId) {
        mcpClientService.closeConnection(serverId);
        repository.deleteByServerId(serverId);
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
            server.setStatus(request.status());
        }
        
        if (request.transport() != null) {
            server.setTransport(request.transport());
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
                server.getStatus(),
                server.getTransport(),
                server.getLastUpdated()
        );
    }

    @Transactional
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
        return Mono.fromSupplier(() -> repository.findByServerId(serverId))
            .flatMap(serverOpt -> serverOpt
                .map(Mono::just)
                .orElseGet(() -> Mono.error(new ResponseStatusException(NOT_FOUND, 
                    "MCP server not found: " + serverId))))
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
        
        return Mono.fromSupplier(() -> repository.findByServerId(serverId))
            .flatMap(serverOpt -> serverOpt
                .map(Mono::just)
                .orElseGet(() -> Mono.error(new ResponseStatusException(NOT_FOUND, 
                    "MCP server not found: " + serverId))))
            .flatMap(server -> {
                // Update sync status to SYNCING
                return updateServerWithRetry(serverId, s -> s.setSyncStatus(SyncStatus.SYNCING))
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
                                s.setSyncStatus(SyncStatus.SYNCED);
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
                        return updateServerWithRetry(serverId, s -> s.setSyncStatus(SyncStatus.SYNC_FAILED))
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
            entity.setStatus(newStatus);
            entity.setLastUpdated(Instant.now());
        })
        .doOnSuccess(updated -> {
            server.setStatus(updated.getStatus());
            server.setLastUpdated(updated.getLastUpdated());
        })
        .onErrorResume(ObjectOptimisticLockingFailureException.class, ex -> {
            log.warn("Optimistic lock conflict while updating status for server {} – keeping existing value", serverId, ex);
            return Mono.empty();
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
        final int maxAttempts = 3;
        return Mono.fromCallable(() -> {
            int attempt = 0;
            while (true) {
                try {
                    McpServer server = repository.findByServerId(serverId)
                        .orElseThrow(() -> new ResponseStatusException(NOT_FOUND,
                            "MCP server not found: " + serverId));
                    updateFn.accept(server);
                    return repository.save(server);
                } catch (ObjectOptimisticLockingFailureException ex) {
                    attempt++;
                    log.debug("Optimistic locking conflict while updating server {} (attempt {}/{})",
                            serverId, attempt, maxAttempts);
                    if (attempt >= maxAttempts) {
                        throw ex;
                    }
                    try {
                        Thread.sleep(25L * attempt);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Interrupted during optimistic lock retry", interrupted);
                    }
                }
            }
        });
    }

    // ===== Public API Methods (Blocking for REST Controllers) =====

    /**
     * Holt Capabilities aus DB Cache (TTL: 5 Min). Falls expired → live fetch.
     * 
     * @param serverId Die Server-ID
     * @return Capabilities DTO
     */
    public app.chatbot.mcp.dto.McpCapabilitiesDto getCapabilities(String serverId) {
        McpServer server = repository.findByServerId(serverId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "MCP server not found"));

        // Prüfe Cache-TTL (5 Minuten)
        if (server.getLastSyncedAt() != null 
            && Duration.between(server.getLastSyncedAt(), Instant.now()).toMinutes() < 5
            && server.getSyncStatus() == SyncStatus.SYNCED) {
            
            // Cache noch gültig → deserialisiere aus DB
            try {
                List<McpSchema.Tool> tools = objectMapper.readValue(
                    server.getToolsCache() != null ? server.getToolsCache() : "[]",
                    new com.fasterxml.jackson.core.type.TypeReference<List<McpSchema.Tool>>() {});
                List<McpSchema.Resource> resources = objectMapper.readValue(
                    server.getResourcesCache() != null ? server.getResourcesCache() : "[]",
                    new com.fasterxml.jackson.core.type.TypeReference<List<McpSchema.Resource>>() {});
                List<McpSchema.Prompt> prompts = objectMapper.readValue(
                    server.getPromptsCache() != null ? server.getPromptsCache() : "[]",
                    new com.fasterxml.jackson.core.type.TypeReference<List<McpSchema.Prompt>>() {});

                log.debug("Serving capabilities from cache for server {}", serverId);
                return new app.chatbot.mcp.dto.McpCapabilitiesDto(
                    tools,
                    resources,
                    prompts,
                    new app.chatbot.mcp.dto.McpCapabilitiesDto.ServerInfo(server.getName(), "1.0")
                );
            } catch (Exception ex) {
                log.warn("Failed to deserialize cached capabilities for server {}, fetching live", serverId, ex);
            }
        }

        // Cache abgelaufen oder ungültig → live fetch mit .block()
        log.debug("Cache expired for server {}, fetching live capabilities", serverId);
        McpServer synced = syncCapabilitiesAsync(serverId)
                .block(Duration.ofSeconds(30));
        
        if (synced == null) {
            throw new IllegalStateException("Sync returned null for server " + serverId);
        }

        // Nach Sync: Nochmal deserialisieren
        try {
            List<McpSchema.Tool> tools = objectMapper.readValue(
                synced.getToolsCache() != null ? synced.getToolsCache() : "[]",
                new com.fasterxml.jackson.core.type.TypeReference<List<McpSchema.Tool>>() {});
            List<McpSchema.Resource> resources = objectMapper.readValue(
                synced.getResourcesCache() != null ? synced.getResourcesCache() : "[]",
                new com.fasterxml.jackson.core.type.TypeReference<List<McpSchema.Resource>>() {});
            List<McpSchema.Prompt> prompts = objectMapper.readValue(
                synced.getPromptsCache() != null ? synced.getPromptsCache() : "[]",
                new com.fasterxml.jackson.core.type.TypeReference<List<McpSchema.Prompt>>() {});

            return new app.chatbot.mcp.dto.McpCapabilitiesDto(
                tools,
                resources,
                prompts,
                new app.chatbot.mcp.dto.McpCapabilitiesDto.ServerInfo(synced.getName(), "1.0")
            );
        } catch (Exception ex) {
            log.error("Failed to deserialize capabilities after sync for server {}", serverId, ex);
            throw new IllegalStateException("Failed to parse capabilities: " + ex.getMessage(), ex);
        }
    }

    /**
     * Triggert manuellen Sync (blocking für REST Endpoint).
     * 
     * @param serverId Die Server-ID
     * @return Sync-Status DTO
     */
    public app.chatbot.mcp.dto.SyncStatusDto syncCapabilitiesBlocking(String serverId) {
        try {
            McpServer synced = syncCapabilitiesAsync(serverId)
                    .block(Duration.ofSeconds(30));
            
            if (synced == null) {
                throw new IllegalStateException("Sync returned null");
            }

            return new app.chatbot.mcp.dto.SyncStatusDto(
                synced.getServerId(),
                synced.getSyncStatus(),
                synced.getLastSyncedAt(),
                "Sync completed successfully"
            );
        } catch (Exception ex) {
            log.error("Manual sync failed for server {}", serverId, ex);
            
            // Update status to SYNC_FAILED
            McpServer server = repository.findByServerId(serverId).orElseThrow();
            server.setSyncStatus(SyncStatus.SYNC_FAILED);
            repository.save(server);

            return new app.chatbot.mcp.dto.SyncStatusDto(
                serverId,
                SyncStatus.SYNC_FAILED,
                null,
                "Sync failed: " + ex.getMessage()
            );
        }
    }

    private String generateServerId() {
        return "mcp-" + UUID.randomUUID();
    }

    // ===== Helper Methods mit separaten Transaktionen =====
    // (Werden von McpConnectionService.connectAndSync() verwendet)

    /**
     * Lädt Server und setzt initialen Status (eigene Transaktion).
     * MUSS public sein damit Spring @Transactional anwenden kann!
     */
    @Transactional
    public McpServer loadAndUpdateStatus(String serverId, McpServerStatus status, SyncStatus syncStatus) {
        McpServer server = repository.findByServerId(serverId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "MCP server not found: " + serverId));
        server.setStatus(status);
        server.setSyncStatus(syncStatus);
        return repository.save(server);
    }

    /**
     * Updated Server Status in separater kurzer Transaktion.
     * MUSS public sein damit Spring @Transactional anwenden kann!
     */
    @Transactional
    public McpServer updateServerStatus(String serverId, McpServerStatus status, SyncStatus syncStatus) {
        McpServer server = repository.findByServerId(serverId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "MCP server not found: " + serverId));
        server.setStatus(status);
        if (syncStatus != null) {
            server.setSyncStatus(syncStatus);
        }
        return repository.save(server);
    }

    /**
     * Speichert Capabilities und markiert als SYNCED (eigene Transaktion).
     * MUSS public sein damit Spring @Transactional anwenden kann!
     */
    @Transactional
    public McpServer saveCapabilitiesAndMarkSynced(String serverId, String toolsJson, 
                                                      String resourcesJson, String promptsJson) {
        McpServer server = repository.findByServerId(serverId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "MCP server not found: " + serverId));
        server.setToolsCache(toolsJson);
        server.setResourcesCache(resourcesJson);
        server.setPromptsCache(promptsJson);
        server.setLastSyncedAt(Instant.now());
        server.setSyncStatus(SyncStatus.SYNCED);
        return repository.save(server);
    }

    /**
     * Setzt Status auf ERROR (eigene Transaktion).
     * MUSS public sein damit Spring @Transactional anwenden kann!
     */
    @Transactional
    public void updateServerToError(String serverId, String message) {
        repository.findByServerId(serverId).ifPresent(server -> {
            server.setStatus(McpServerStatus.ERROR);
            repository.save(server);
            statusPublisher.publishStatusUpdate(serverId, server.getName(), 
                    McpServerStatus.ERROR, server.getSyncStatus(), message);
        });
    }

    /**
     * Setzt Status auf SYNC_FAILED (eigene Transaktion).
     * MUSS public sein damit Spring @Transactional anwenden kann!
     */
    @Transactional
    public void updateServerToSyncFailed(String serverId, String message) {
        repository.findByServerId(serverId).ifPresent(server -> {
            server.setSyncStatus(SyncStatus.SYNC_FAILED);
            repository.save(server);
            statusPublisher.publishStatusUpdate(serverId, server.getName(), 
                    server.getStatus(), SyncStatus.SYNC_FAILED, message);
        });
    }
}
