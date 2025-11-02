package app.chatbot.mcp;

import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import app.chatbot.mcp.config.McpProperties;
import app.chatbot.security.SecretEncryptor;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;

/**
 * Service für die Verwaltung von MCP-Client-Verbindungen zu externen MCP-Servern.
 * Unterstützt sowohl SSE als auch Streamable HTTP Transports.
 */
@Service
@Slf4j
public class McpClientService {

    private final McpProperties properties;
    private final SecretEncryptor encryptor;
    private final Map<String, McpSyncClient> activeConnections = new ConcurrentHashMap<>();

    public McpClientService(McpProperties properties,
                           SecretEncryptor encryptor) {
        this.properties = properties;
        this.encryptor = encryptor;
    }

    /**
     * Stellt eine Verbindung zu einem MCP-Server her und cached den Client.
     *
     * @param server Der MCP-Server
     * @return Der verbundene MCP-Client
     */
    public McpSyncClient getOrCreateClient(McpServer server) {
        return activeConnections.computeIfAbsent(server.getServerId(), key -> {
            try {
                String decryptedApiKey = decryptApiKey(server);
                McpEndpointResolver.Endpoint endpoint = McpEndpointResolver
                        .resolveCandidates(server, server.getTransport())
                        .get(0);

                McpClientTransport transport = createTransport(endpoint, decryptedApiKey, server.getTransport());

                McpSyncClient client = McpClient
                        .sync(transport)
                        .clientInfo(new McpSchema.Implementation(properties.clientName(), properties.clientVersion()))
                        .requestTimeout(properties.requestTimeout())
                        .initializationTimeout(properties.initializationTimeout())
                        .build();

                client.initialize();
                log.info("MCP client connected to server {} using {} transport at {}",
                        server.getServerId(), server.getTransport(), endpoint.fullUrl());

                return client;
            } catch (Exception ex) {
                log.error("Failed to create MCP client for server {}", server.getServerId(), ex);
                throw new McpClientException("Failed to connect to MCP server: " + ex.getMessage(), ex);
            }
        });
    }

    /**
     * Listet alle verfügbaren Tools eines MCP-Servers auf.
     *
     * @param server Der MCP-Server
     * @return Liste der verfügbaren Tools
     */
    public List<McpSchema.Tool> listTools(McpServer server) {
        try {
            McpSyncClient client = getOrCreateClient(server);
            McpSchema.ListToolsResult result = client.listTools();
            return result != null && result.tools() != null ? result.tools() : List.of();
        } catch (Exception ex) {
            log.error("Failed to list tools for server {}", server.getServerId(), ex);
            throw new McpClientException("Failed to list tools: " + ex.getMessage(), ex);
        }
    }

    /**
     * Listet alle verfügbaren Resources eines MCP-Servers auf.
     *
     * @param server Der MCP-Server
     * @return Liste der verfügbaren Resources
     */
    public List<McpSchema.Resource> listResources(McpServer server) {
        try {
            McpSyncClient client = getOrCreateClient(server);
            McpSchema.ListResourcesResult result = client.listResources();
            return result != null && result.resources() != null ? result.resources() : List.of();
        } catch (Exception ex) {
            log.error("Failed to list resources for server {}", server.getServerId(), ex);
            throw new McpClientException("Failed to list resources: " + ex.getMessage(), ex);
        }
    }

    /**
     * Listet alle verfügbaren Prompts eines MCP-Servers auf.
     *
     * @param server Der MCP-Server
     * @return Liste der verfügbaren Prompts
     */
    public List<McpSchema.Prompt> listPrompts(McpServer server) {
        try {
            McpSyncClient client = getOrCreateClient(server);
            McpSchema.ListPromptsResult result = client.listPrompts();
            return result != null && result.prompts() != null ? result.prompts() : List.of();
        } catch (Exception ex) {
            log.error("Failed to list prompts for server {}", server.getServerId(), ex);
            throw new McpClientException("Failed to list prompts: " + ex.getMessage(), ex);
        }
    }

    /**
     * Ruft ein Tool auf einem MCP-Server auf.
     *
     * @param server Der MCP-Server
     * @param toolName Name des Tools
     * @param arguments Tool-Argumente
     * @return Das Ergebnis des Tool-Aufrufs
     */
    public McpSchema.CallToolResult callTool(McpServer server, String toolName, Map<String, Object> arguments) {
        try {
            McpSyncClient client = getOrCreateClient(server);
            McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(toolName, arguments);
            McpSchema.CallToolResult result = client.callTool(request);

            log.info("Tool {} called on server {} with result: {}",
                    toolName, server.getServerId(), result != null ? "success" : "null");

            return result;
        } catch (Exception ex) {
            log.error("Failed to call tool {} on server {}", toolName, server.getServerId(), ex);
            throw new McpClientException("Failed to call tool: " + ex.getMessage(), ex);
        }
    }

    /**
     * Schließt die Verbindung zu einem MCP-Server.
     *
     * @param serverId Die Server-ID
     */
    public void closeConnection(String serverId) {
        McpSyncClient client = activeConnections.remove(serverId);
        if (client != null) {
            try {
                client.closeGracefully();
                log.info("Closed MCP client connection to server {}", serverId);
            } catch (Exception ex) {
                log.warn("Error closing MCP client for server {}", serverId, ex);
            }
        }
    }

    /**
     * Schließt alle aktiven Verbindungen.
     */
    public void closeAllConnections() {
        activeConnections.forEach((serverId, client) -> {
            try {
                client.closeGracefully();
                log.info("Closed MCP client connection to server {}", serverId);
            } catch (Exception ex) {
                log.warn("Error closing MCP client for server {}", serverId, ex);
            }
        });
        activeConnections.clear();
    }

    /**
     * Gibt die Anzahl aktiver Verbindungen zurück.
     *
     * @return Anzahl aktiver Verbindungen
     */
    public int getActiveConnectionCount() {
        return activeConnections.size();
    }

    private String decryptApiKey(McpServer server) {
        if (!StringUtils.hasText(server.getApiKey())) {
            return null;
        }
        try {
            return encryptor.decrypt(server.getApiKey());
        } catch (Exception ex) {
            log.warn("Failed to decrypt API key for server {}", server.getServerId(), ex);
            return null;
        }
    }

    private McpClientTransport createTransport(McpEndpointResolver.Endpoint endpoint,
                                               String decryptedApiKey,
                                               McpTransport transport) {
        HttpClient.Builder clientBuilder = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout());

        java.net.http.HttpRequest.Builder requestBuilder = java.net.http.HttpRequest.newBuilder();
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
