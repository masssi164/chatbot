package app.chatbot.mcp;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Iterator;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import app.chatbot.mcp.config.McpProperties;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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

    /**
     * Result of a connection test attempt.
     *
     * @param success Whether the connection was successful
     * @param toolCount Number of tools discovered (0 if failed)
     * @param message Error message if failed, null otherwise
     */
    public record ConnectionResult(boolean success, int toolCount, String message) {}

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
            return (recommendedTransport != null && server.getTransport() != recommendedTransport)
                    || (recommendedBaseUrl != null && !recommendedBaseUrl.equals(server.getBaseUrl()));
        }
    }

    public ConnectionResult testConnection(String baseUrl, McpTransport transport, String apiKey) {
        McpServer tempServer = new McpServer();
        tempServer.setServerId("test");
        tempServer.setName("test");
        tempServer.setBaseUrl(baseUrl);
        tempServer.setTransport(transport);
        tempServer.setStatus(McpServerStatus.CONNECTING);

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
        McpTransport[] order = server.getTransport() == McpTransport.STREAMABLE_HTTP
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
                && server.getTransport() != result.recommendedTransport()) {
            log.info("Updating server {} transport from {} to {}",
                    server.getServerId(), server.getTransport(), result.recommendedTransport());
            server.setTransport(result.recommendedTransport());
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
