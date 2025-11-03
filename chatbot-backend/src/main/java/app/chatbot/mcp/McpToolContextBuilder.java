package app.chatbot.mcp;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

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

        // Finde alle CONNECTED Server mit gültigem Sync-Status
        List<McpServer> servers = repository.findAll()
                .stream()
                .filter(server -> server.getStatus() == McpServerStatus.CONNECTED)
                .filter(server -> server.getSyncStatus() == SyncStatus.SYNCED)
                .filter(this::isCacheValid)
                .toList();

        if (servers.isEmpty()) {
            log.debug("No connected MCP servers with valid sync status available");
            return;
        }

        int serverCount = 0;
        for (McpServer server : servers) {
            try {
                ObjectNode mcpTool = createMcpServerConfig(server);
                toolsNode.add(mcpTool);
                serverCount++;
                
                log.debug("Added MCP server '{}' to OpenAI request", server.getServerId());
            } catch (Exception ex) {
                log.error("Failed to create MCP config for server {}: {}", 
                    server.getServerId(), ex.getMessage());
            }
        }

        log.debug("Added {} MCP servers to OpenAI Responses API request", serverCount);
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

