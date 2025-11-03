package app.chatbot.mcp;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.modelcontextprotocol.spec.McpSchema;
import app.chatbot.security.EncryptionException;
import app.chatbot.security.SecretEncryptor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Baut MCP-Server-Konfiguration für OpenAI Responses API auf.
 * 
 * Die Responses API unterstützt MCP-Server nativ über das Format:
 * {
 *   "type": "mcp",
 *   "server_label": "my-server",
 *   "server_url": "https://example.com/mcp",
 *   "headers": { "Authorization": "Bearer token" },
 *   "require_approval": "never"
 * }
 * 
 * Die API ruft die Tools automatisch vom MCP-Server ab.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class McpToolContextBuilder {

    private final McpServerRepository repository;
    private final SecretEncryptor secretEncryptor;
    private final ObjectMapper objectMapper;
    private final McpClientService clientService;
    
    // TTL für Cache: 5 Minuten - wird für Statusprüfung verwendet
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    /**
     * Erweitert OpenAI Request Payload mit MCP-Server-Konfigurationen im Responses API Format.
     * 
     * @param payload Das OpenAI Request Payload (wird modifiziert)
     */
    public void augmentPayload(ObjectNode payload) {
        if (payload == null) {
            return;
        }

        // Hole Tools-Array (oder erstelle neues)
        ArrayNode toolsNode = payload.has("tools") 
            ? (ArrayNode) payload.get("tools") 
            : payload.putArray("tools");

        // Finde alle CONNECTED Server mit gültigem Sync-Status (Cache kann veraltet sein)
        List<McpServer> servers = repository.findAll()
                .stream()
                .filter(server -> server.getStatus() == McpServerStatus.CONNECTED)
                .filter(server -> server.getSyncStatus() == SyncStatus.SYNCED)
                .toList();

        if (servers.isEmpty()) {
            log.debug("No connected MCP servers available");
            return;
        }

        int serverCount = 0;
        int staleCount = 0;
        for (McpServer server : servers) {
            try {
                if (!ensureCachedCapabilities(server)) {
                    log.debug("Skipping MCP server '{}' – no cached capabilities available", server.getServerId());
                    continue;
                }

                boolean cacheValid = isCacheValid(server);
                if (!cacheValid) {
                    staleCount++;
                    log.debug("Using stale MCP cache for server {} (lastSyncedAt={})", 
                        server.getServerId(), server.getLastSyncedAt());
                }
                ObjectNode mcpTool = createMcpServerConfig(server);
                toolsNode.add(mcpTool);
                serverCount++;
                
                log.debug("Added MCP server '{}' to OpenAI request", server.getServerId());
            } catch (Exception ex) {
                log.error("Failed to create MCP config for server {}: {}", 
                    server.getServerId(), ex.getMessage());
            }
        }

        if (serverCount == 0) {
            log.debug("No MCP servers with cached capabilities available for OpenAI request");
        } else {
            log.debug("Added {} MCP server(s) to OpenAI Responses API request{}",
                serverCount,
                staleCount > 0 ? (" (" + staleCount + " stale cache" + (staleCount == 1 ? "" : "s") + ")") : "");
        }
    }

    /**
     * Prüft ob überhaupt gecachte Capabilities vorhanden sind.
     */
    private boolean hasCachedCapabilities(McpServer server) {
        return StringUtils.hasText(server.getToolsCache())
                || StringUtils.hasText(server.getResourcesCache())
                || StringUtils.hasText(server.getPromptsCache());
    }

    private boolean ensureCachedCapabilities(McpServer server) {
        if (hasCachedCapabilities(server)) {
            return true;
        }

        try {
            List<McpSchema.Tool> tools = clientService.listToolsAsync(server.getServerId())
                    .defaultIfEmpty(List.of())
                    .block(Duration.ofSeconds(10));
            List<McpSchema.Resource> resources = clientService.listResourcesAsync(server.getServerId())
                    .defaultIfEmpty(List.of())
                    .block(Duration.ofSeconds(10));
            List<McpSchema.Prompt> prompts = clientService.listPromptsAsync(server.getServerId())
                    .defaultIfEmpty(List.of())
                    .block(Duration.ofSeconds(10));

            boolean hasData = (tools != null && !tools.isEmpty())
                    || (resources != null && !resources.isEmpty())
                    || (prompts != null && !prompts.isEmpty());

            if (!hasData) {
                return false;
            }

            String toolsJson = objectMapper.writeValueAsString(tools != null ? tools : List.of());
            String resourcesJson = objectMapper.writeValueAsString(resources != null ? resources : List.of());
            String promptsJson = objectMapper.writeValueAsString(prompts != null ? prompts : List.of());

            server.setToolsCache(toolsJson);
            server.setResourcesCache(resourcesJson);
            server.setPromptsCache(promptsJson);
            server.setLastSyncedAt(Instant.now());
            server.setSyncStatus(SyncStatus.SYNCED);
            repository.save(server);
            return true;
        } catch (Exception ex) {
            log.warn("Failed to refresh cached capabilities for server {}: {}", server.getServerId(), ex.getMessage());
            return false;
        }
    }

    /**
     * Prüft ob der Sync-Status eines Servers noch aktuell ist (TTL-Check).
     */
    private boolean isCacheValid(McpServer server) {
        if (server.getLastSyncedAt() == null) {
            return false;
        }
        Duration age = Duration.between(server.getLastSyncedAt(), Instant.now());
        return age.compareTo(CACHE_TTL) < 0;
    }

    /**
     * Erstellt MCP-Server-Konfiguration im OpenAI Responses API Format.
     * 
     * Format:
     * {
     *   "type": "mcp",
     *   "server_label": "my-server",
     *   "server_url": "https://example.com/mcp",
     *   "headers": { "Authorization": "Bearer token" },
     *   "require_approval": "never"
     * }
     */
    private ObjectNode createMcpServerConfig(McpServer server) throws EncryptionException {
        ObjectNode config = objectMapper.createObjectNode();
        
        // Type: immer "mcp"
        config.put("type", "mcp");
        
        // Server-Label: Eindeutige Kennung für den Server
        config.put("server_label", server.getServerId());
        
        // Server-URL: Basis-URL des MCP-Servers
        config.put("server_url", server.getBaseUrl());
        
        // Headers: Optional, für Authentifizierung
        if (StringUtils.hasText(server.getApiKey())) {
            String decryptedApiKey = secretEncryptor.decrypt(server.getApiKey());
            ObjectNode headers = config.putObject("headers");
            headers.put("Authorization", "Bearer " + decryptedApiKey);
        }
        
        // Require Approval: "never" für automatische Ausführung
        config.put("require_approval", "never");
        
        return config;
    }
}
