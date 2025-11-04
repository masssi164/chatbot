package app.chatbot.mcp;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import app.chatbot.mcp.config.McpProperties;
import app.chatbot.mcp.events.McpServerStatusPublisher;
import app.chatbot.security.EncryptionException;
import app.chatbot.security.SecretEncryptor;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * Service for verifying MCP server connections following Command-Query Separation (CQS) principle.
 * <p>
 * This service implements the CQS pattern as recommended by Microsoft Azure architecture patterns:
 * <ul>
 *   <li><strong>Queries</strong> ({@link #verify(McpServer, String)}): Pure read operations that return
 *       verification results WITHOUT modifying server state</li>
 *   <li><strong>Commands</strong> ({@link #updateServerConfiguration(McpServer, VerificationResult)}):
 *       State-changing operations that apply configuration updates</li>
 * </ul>
 * <p>
 * <strong>Usage Example:</strong>
 * <pre>{@code
 * // Step 1: Verify connection (Query - no side effects)
 * VerificationResult result = connectionService.verify(server, apiKey);
 *
 * // Step 2: Check if successful and configuration changed
 * if (result.isSuccessful() && result.hasConfigurationChanges(server)) {
 *     // Step 3: Optionally apply recommended changes (Command)
 *     boolean updated = connectionService.updateServerConfiguration(server, result);
 *     if (updated) {
 *         repository.save(server);
 *     }
 * }
 * }</pre>
 * <p>
 * This separation provides:
 * <ul>
 *   <li>Testability: Queries can be tested without side effects</li>
 *   <li>Composability: Results can be inspected before applying changes</li>
 *   <li>Clarity: Clear distinction between reading state and modifying it</li>
 * </ul>
 *
 * @see <a href="https://learn.microsoft.com/en-us/azure/architecture/patterns/cqrs">CQRS Pattern - Azure Architecture</a>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class McpConnectionService {

    private final McpProperties properties;
    private final McpServerRepository repository;
    private final SecretEncryptor secretEncryptor;
    private final McpSessionRegistry sessionRegistry;
    private final ObjectMapper objectMapper;
    private final McpServerStatusPublisher statusPublisher;

    /**
     * Result of a connection test attempt.
     *
     * @param success Whether the connection was successful
     * @param toolCount Number of tools discovered (0 if failed)
     * @param message Error message if failed, null otherwise
     */
    public record ConnectionResult(boolean success, int toolCount, String message) {}

    /**
     * IDEMPOTENT connection and sync operation for Event-Driven Architecture.
     * 
     * Called by McpConnectionEventListener after McpServerUpdatedEvent.
     * 
     * ✅ Idempotent: Safe to call multiple times with same input
     * ✅ Sequential: Spring EventListener guarantees sequential processing per listener
     * ✅ Efficient: Checks if already connected/synced before doing work
     * 
     * Flow:
     * 1. Check if already connected + recently synced → Skip
     * 2. Set CONNECTING status
     * 3. Open MCP session (15s timeout)
     * 4. Set CONNECTED + SYNCING status
     * 5. Fetch tools/resources/prompts
     * 6. Save to DB + set SYNCED status
     * 7. Publish SSE event to frontend
     * 
     * Microsoft Best Practice:
     * "An idempotent operation is one that has no extra effect if it's called 
     * more than once with the same input parameters"
     * 
     * @param serverId The server ID to connect
     * @throws RuntimeException if connection/sync fails
     */
    /**
     * Reactive connect and sync operation.
     * Returns Mono<Void> to be used as fire-and-forget with .subscribe()
     */
    public Mono<Void> connectAndSync(String serverId) {
        log.info("Starting idempotent connect and sync for server {}", serverId);
        
        return repository.findByServerId(serverId)
                .switchIfEmpty(Mono.error(new RuntimeException("MCP server not found: " + serverId)))
                .flatMap(server -> {
                    // 1. Idempotent Check: Skip if already connected + recently synced
                    if (server.getStatusEnum() == McpServerStatus.CONNECTED 
                        && server.getSyncStatusEnum() == SyncStatus.SYNCED
                        && server.getLastSyncedAt() != null
                        && Duration.between(server.getLastSyncedAt(), Instant.now()).toMinutes() < 5) {
                        log.info("Server {} already connected and recently synced ({}), skipping", 
                                serverId, server.getLastSyncedAt());
                        return Mono.empty(); // ✅ Idempotent: Multiple calls do nothing
                    }
                    
                    // 2. Set CONNECTING status
                    updateStatus(serverId, McpServerStatus.CONNECTING, SyncStatus.NEVER_SYNCED);
                    statusPublisher.publishStatusUpdate(serverId, server.getName(), 
                            McpServerStatus.CONNECTING, SyncStatus.NEVER_SYNCED, "Connecting to MCP server...");
                    
                    // 3. Decrypt API Key
                    String decryptedApiKey = null;
                    if (StringUtils.hasText(server.getApiKey())) {
                        try {
                            decryptedApiKey = secretEncryptor.decrypt(server.getApiKey());
                        } catch (EncryptionException ex) {
                            log.error("Failed to decrypt API key for server {}", serverId, ex);
                            updateStatus(serverId, McpServerStatus.ERROR, null);
                            statusPublisher.publishStatusUpdate(serverId, server.getName(), 
                                    McpServerStatus.ERROR, SyncStatus.NEVER_SYNCED, "Failed to decrypt API key");
                            return Mono.error(ex);
                        }
                    }
                    
                    // 4. Open MCP Session (reactive, 15s timeout)
                    log.info("Opening MCP session for server {}", serverId);
                    return sessionRegistry.getOrCreateSession(serverId)
                            .timeout(properties.initializationTimeout())
                            .flatMap(client -> {
                                // 5. Set CONNECTED + SYNCING
                                updateStatus(serverId, McpServerStatus.CONNECTED, SyncStatus.SYNCING);
                                statusPublisher.publishStatusUpdate(serverId, server.getName(), 
                                        McpServerStatus.CONNECTED, SyncStatus.SYNCING, "Fetching tools, resources and prompts...");
                                
                                // 6. Fetch Capabilities (reactive, 10s timeout each)
                                log.info("Fetching capabilities for server {}", serverId);
                                
                                return client.listTools()
                                        .timeout(Duration.ofSeconds(10))
                                        .flatMap(toolsResult -> {
                                            List<McpSchema.Tool> tools = toolsResult != null && toolsResult.tools() != null 
                                                    ? toolsResult.tools() : List.of();
                                            
                                            var capabilities = client.getServerCapabilities();
                                            boolean supportsResources = capabilities != null && capabilities.resources() != null;
                                            boolean supportsPrompts = capabilities != null && capabilities.prompts() != null;
                                            
                                            Mono<List<McpSchema.Resource>> resourcesMono;
                                            if (supportsResources) {
                                                resourcesMono = client.listResources()
                                                        .timeout(Duration.ofSeconds(10))
                                                        .map(result -> result != null && result.resources() != null 
                                                                ? result.resources() : List.<McpSchema.Resource>of());
                                            } else {
                                                log.info("Server {} does not advertise resources capability, skipping resource sync", serverId);
                                                resourcesMono = Mono.just(List.of());
                                            }
                                            
                                            Mono<List<McpSchema.Prompt>> promptsMono;
                                            if (supportsPrompts) {
                                                promptsMono = client.listPrompts()
                                                        .timeout(Duration.ofSeconds(10))
                                                        .map(result -> result != null && result.prompts() != null 
                                                                ? result.prompts() : List.<McpSchema.Prompt>of());
                                            } else {
                                                log.info("Server {} does not advertise prompts capability, skipping prompt sync", serverId);
                                                promptsMono = Mono.just(List.of());
                                            }
                                            
                                            return Mono.zip(resourcesMono, promptsMono)
                                                    .flatMap(tuple -> {
                                                        List<McpSchema.Resource> resources = tuple.getT1();
                                                        List<McpSchema.Prompt> prompts = tuple.getT2();
                                                        
                                                        try {
                                                            // 7. Serialize Capabilities
                                                            String toolsJson = objectMapper.writeValueAsString(tools);
                                                            String resourcesJson = objectMapper.writeValueAsString(resources);
                                                            String promptsJson = objectMapper.writeValueAsString(prompts);
                                                            
                                                            // 8. Save to DB + set SYNCED
                                                            saveCapabilitiesAndMarkSynced(serverId, toolsJson, resourcesJson, promptsJson);
                                                            
                                                            log.info("Successfully synced server {}: {} tools, {} resources, {} prompts", 
                                                                    serverId, tools.size(), resources.size(), prompts.size());
                                                            
                                                            // 9. Publish SSE Event
                                                            return repository.findByServerId(serverId)
                                                                    .switchIfEmpty(Mono.error(new RuntimeException("MCP server not found: " + serverId)))
                                                                    .flatMap(updatedServer -> {
                                                                        try {
                                                                            statusPublisher.publishStatusWithCapabilities(
                                                                                    updatedServer, 
                                                                                    "Sync completed",
                                                                                    objectMapper.readTree(toolsJson),
                                                                                    objectMapper.readTree(resourcesJson),
                                                                                    objectMapper.readTree(promptsJson)
                                                                            );
                                                                            return Mono.empty();
                                                                        } catch (JsonProcessingException ex) {
                                                                            return Mono.error(ex);
                                                                        }
                                                                    });
                                                        } catch (JsonProcessingException ex) {
                                                            log.error("Failed to serialize capabilities for server {}", serverId, ex);
                                                            updateStatus(serverId, McpServerStatus.CONNECTED, SyncStatus.SYNC_FAILED);
                                                            
                                                            // Publish error event to frontend (async - fire and forget)
                                                            repository.findByServerId(serverId)
                                                                    .doOnNext(srv -> {
                                                                        statusPublisher.publishStatusUpdate(serverId, srv.getName(), 
                                                                                McpServerStatus.CONNECTED, SyncStatus.SYNC_FAILED, 
                                                                                "Failed to serialize capabilities: " + ex.getMessage());
                                                                    })
                                                                    .subscribe();
                                                            
                                                            return Mono.error(new RuntimeException("Serialization failed", ex));
                                                        }
                                                    });
                                        });
                            });
                })
                .onErrorResume(ex -> {
                    log.error("Connect and sync failed for server {}", serverId, ex);
                    updateStatus(serverId, McpServerStatus.ERROR, null);
                    
                    // Publish error event to frontend (async - fire and forget)
                    repository.findByServerId(serverId)
                            .doOnNext(server -> {
                                statusPublisher.publishStatusUpdate(serverId, server.getName(), 
                                        McpServerStatus.ERROR, SyncStatus.NEVER_SYNCED, 
                                        "Connection failed: " + ex.getMessage());
                            })
                            .subscribe();
                    
                    return Mono.error(new RuntimeException("Connect/sync failed", ex));
                })
                .then(); // Convert to Mono<Void>
    }
    
    @Transactional
    private void updateStatus(String serverId, McpServerStatus status, SyncStatus syncStatus) {
        repository.findByServerId(serverId)
                .flatMap(server -> {
                    server.setStatusEnum(status);
                    if (syncStatus != null) {
                        server.setSyncStatusEnum(syncStatus);
                    }
                    return repository.save(server);
                })
                .subscribe();
    }
    
    @Transactional
    private void saveCapabilitiesAndMarkSynced(String serverId, String toolsJson, 
                                                String resourcesJson, String promptsJson) {
        repository.findByServerId(serverId)
                .flatMap(server -> {
                    server.setToolsCache(toolsJson);
                    server.setResourcesCache(resourcesJson);
                    server.setPromptsCache(promptsJson);
                    server.setLastSyncedAt(Instant.now());
                    server.setSyncStatusEnum(SyncStatus.SYNCED);
                    return repository.save(server);
                })
                .subscribe();
    }

    /**
     * Result of a connection test attempt.
     *
     * @param success Whether the connection was successful
     * @param toolCount Number of tools discovered (0 if failed)
     * @param message Error message if failed, null otherwise
     */
    public record ConnectionResult_OLD(boolean success, int toolCount, String message) {}

    /**
     * Detailed verification result following CQS principle.
     * <p>
     * This record is a pure Query result - it contains NO side effects.
     * It provides recommendations for configuration updates that can be
     * applied separately via the {@link #updateServerConfiguration(McpServer, VerificationResult)} method.
     *
     * @param status Connection status
     * @param toolCount Number of tools discovered
     * @param errorMessage Error message if failed
     * @param recommendedTransport Recommended transport based on successful connection (may differ from initial)
     * @param recommendedBaseUrl Recommended base URL based on successful endpoint (may differ from initial)
     */
    public record VerificationResult(
            McpServerStatus status,
            int toolCount,
            String errorMessage,
            McpTransport recommendedTransport,
            String recommendedBaseUrl
    ) {
        public boolean isSuccessful() {
            return status == McpServerStatus.CONNECTED;
        }

        public boolean hasConfigurationChanges(McpServer server) {
            return (recommendedTransport != null && server.getTransportEnum() != recommendedTransport)
                    || (recommendedBaseUrl != null && !recommendedBaseUrl.equals(server.getBaseUrl()));
        }
    }

    public ConnectionResult testConnection(String baseUrl, McpTransport transport, String apiKey) {
        McpServer tempServer = new McpServer();
        tempServer.setServerId("test");
        tempServer.setName("test");
        tempServer.setBaseUrl(baseUrl);
        tempServer.setTransportEnum(transport);
        tempServer.setStatusEnum(McpServerStatus.CONNECTING);

        try {
            VerificationResult result = verify(tempServer, apiKey);
            return new ConnectionResult(
                    result.isSuccessful(),
                    result.toolCount(),
                    result.errorMessage()
            );
        } catch (Exception ex) {
            log.error("Test connection failed for {}", baseUrl, ex);
            return new ConnectionResult(false, 0, ex.getMessage());
        }
    }

    /**
     * Verifies MCP server connection following Command-Query Separation (CQS) principle.
     * <p>
     * This is a PURE QUERY method - it performs NO side effects on the server object.
     * It returns a {@link VerificationResult} containing the connection status and
     * recommendations for configuration updates.
     * <p>
     * Use {@link #updateServerConfiguration(McpServer, VerificationResult)} separately
     * to apply the recommended configuration changes.
     * <p>
     * <strong>Microsoft CQS Best Practice:</strong>
     * <blockquote>
     * "Queries never alter data. Instead, they return data transfer objects (DTOs)
     * that present the required data in a convenient format, without any domain logic."
     * </blockquote>
     *
     * @param server The MCP server to verify (NOT modified by this method)
     * @param decryptedApiKey The decrypted API key for authentication
     * @return Verification result with status and configuration recommendations
     */
    public VerificationResult verify(McpServer server, String decryptedApiKey) {
        McpTransport[] order = server.getTransportEnum() == McpTransport.STREAMABLE_HTTP
                // Respect user preference if explicitly set, otherwise default SSE first
                ? new McpTransport[]{McpTransport.STREAMABLE_HTTP, McpTransport.SSE}
                : new McpTransport[]{McpTransport.SSE};

        Exception lastFailure = null;

        for (McpTransport transport : order) {
            for (McpEndpointResolver.Endpoint endpoint : McpEndpointResolver.resolveCandidates(server, transport)) {
                try {
                    if (transport == McpTransport.SSE) {
                        return attemptSseHandshake(decryptedApiKey, transport, endpoint);
                    }
                    return attemptStreamableHandshake(decryptedApiKey, transport, endpoint);
                } catch (Exception ex) {
                    lastFailure = ex;
                    log.warn("MCP handshake failed for server {} using {} at {}",
                            server.getServerId(), transport, endpoint.fullUrl(), ex);
                }
            }
        }

        String message = lastFailure != null ? lastFailure.getMessage() : "Unable to connect to MCP server";
        return new VerificationResult(McpServerStatus.ERROR, 0, message, null, null);
    }

    /**
     * Updates server configuration based on verification results.
     * <p>
     * This is a COMMAND method - it modifies the server object's state.
     * Should only be called after successful verification to apply recommended changes.
     * <p>
     * <strong>Microsoft CQS Best Practice:</strong>
     * <blockquote>
     * "Commands should represent specific business tasks instead of low-level data updates.
     * Each method either returns state or mutates state, but not both."
     * </blockquote>
     *
     * @param server The MCP server to update
     * @param result The verification result containing recommendations
     * @return true if any configuration was changed, false otherwise
     */
    public boolean updateServerConfiguration(McpServer server, VerificationResult result) {
        if (!result.isSuccessful()) {
            log.warn("Skipping configuration update for unsuccessful verification");
            return false;
        }

        boolean changed = false;

        if (result.recommendedTransport() != null
                && server.getTransportEnum() != result.recommendedTransport()) {
            log.info("Updating server {} transport from {} to {}",
                    server.getServerId(), server.getTransportEnum(), result.recommendedTransport());
            server.setTransportEnum(result.recommendedTransport());
            changed = true;
        }

        if (result.recommendedBaseUrl() != null
                && !result.recommendedBaseUrl().equals(server.getBaseUrl())) {
            log.info("Updating server {} base URL from {} to {}",
                    server.getServerId(), server.getBaseUrl(), result.recommendedBaseUrl());
            server.setBaseUrl(result.recommendedBaseUrl());
            changed = true;
        }

        return changed;
    }

    /**
     * Attempts STREAMABLE_HTTP handshake (pure query - no side effects).
     *
     * @param decryptedApiKey The API key for authentication
     * @param transport The transport protocol used
     * @param endpoint The endpoint to connect to
     * @return Verification result with recommendations
     */
    private VerificationResult attemptStreamableHandshake(String decryptedApiKey,
                                                          McpTransport transport,
                                                          McpEndpointResolver.Endpoint endpoint) {
        McpClientTransport clientTransport = createTransport(endpoint, decryptedApiKey, transport);

        try (McpSyncClient client = McpClient
                .sync(clientTransport)
                .clientInfo(new McpSchema.Implementation(properties.clientName(), properties.clientVersion()))
                .capabilities(McpSchema.ClientCapabilities.builder().build())
                .requestTimeout(properties.requestTimeout())
                .initializationTimeout(properties.initializationTimeout())
                .build()) {

            client.initialize();
            McpSchema.ListToolsResult toolsResult = client.listTools();
            int toolCount = toolsResult != null && toolsResult.tools() != null ? toolsResult.tools().size() : 0;
            client.closeGracefully();

            // Return recommendations without modifying server
            return new VerificationResult(
                    McpServerStatus.CONNECTED,
                    toolCount,
                    null,
                    transport,
                    endpoint.fullUrl()
            );
        }
    }

    /**
     * Attempts SSE handshake (pure query - no side effects).
     *
     * @param decryptedApiKey The API key for authentication
     * @param transport The transport protocol used
     * @param endpoint The endpoint to connect to
     * @return Verification result with recommendations
     * @throws Exception if the handshake fails
     */
    private VerificationResult attemptSseHandshake(String decryptedApiKey,
                                                   McpTransport transport,
                                                   McpEndpointResolver.Endpoint endpoint) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(endpoint.fullUrl()))
                .GET()
                .header("Accept", "text/event-stream")
                .timeout(properties.initializationTimeout());

        if (StringUtils.hasText(decryptedApiKey)) {
            requestBuilder.header("Authorization", "Bearer " + decryptedApiKey.trim());
        }

        HttpResponse<Stream<String>> response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofLines());
        int statusCode = response.statusCode();
        if (statusCode >= 400) {
            throw new IllegalStateException("SSE endpoint responded with HTTP " + statusCode);
        }

        try (Stream<String> lines = response.body()) {
            Iterator<String> iterator = lines.iterator();
            long deadline = System.nanoTime() + properties.initializationTimeout().toNanos();
            while (System.nanoTime() < deadline && iterator.hasNext()) {
                String line = iterator.next();
                if (!StringUtils.hasText(line)) {
                    continue;
                }
                if (line.startsWith("data:")) {
                    // Return recommendations without modifying server
                    return new VerificationResult(
                            McpServerStatus.CONNECTED,
                            0, // SSE handshake doesn't retrieve tool count initially
                            null,
                            transport,
                            endpoint.fullUrl()
                    );
                }
            }
        }

        throw new IllegalStateException("SSE endpoint did not emit data before timeout");
    }

    private McpClientTransport createTransport(McpEndpointResolver.Endpoint endpoint,
                                               String decryptedApiKey,
                                               McpTransport transport) {
        HttpClient.Builder clientBuilder = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout());
        var requestBuilder = java.net.http.HttpRequest.newBuilder();
        if (StringUtils.hasText(decryptedApiKey)) {
            requestBuilder.header("Authorization", "Bearer " + decryptedApiKey.trim());
        }

        if (transport == McpTransport.STREAMABLE_HTTP) {
            return HttpClientStreamableHttpTransport
                    .builder(endpoint.baseUri())
                    .clientBuilder(clientBuilder)
                    .requestBuilder(requestBuilder)
                    .endpoint(endpoint.relativePath())
                    .connectTimeout(properties.connectTimeout())
                    .build();
        }

        return HttpClientSseClientTransport
                .builder(endpoint.baseUri())
                .clientBuilder(clientBuilder)
                .requestBuilder(requestBuilder)
                .sseEndpoint(endpoint.relativePath())
                .connectTimeout(properties.connectTimeout())
                .build();
    }
}
