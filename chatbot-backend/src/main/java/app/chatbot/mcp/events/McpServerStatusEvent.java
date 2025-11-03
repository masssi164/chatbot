package app.chatbot.mcp.events;

import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;

import app.chatbot.mcp.McpServerStatus;
import app.chatbot.mcp.SyncStatus;
import lombok.Builder;
import lombok.Data;

/**
 * Event für MCP Server Status-Änderungen.
 * Wird von McpServerService published und von SSE-Stream konsumiert.
 * 
 * Enhanced Version mit Capabilities (tools, resources, prompts) im Event.
 */
@Data
@Builder
public class McpServerStatusEvent {
    
    private String serverId;
    private String serverName;
    private McpServerStatus connectionStatus;
    private SyncStatus syncStatus;
    private String message;
    private Instant timestamp;
    
    // Capability counts
    private Integer toolCount;
    private Integer resourceCount;
    private Integer promptCount;
    
    // Capabilities JSON (optional - nur bei SYNCED Events)
    private JsonNode toolsJson;
    private JsonNode resourcesJson;
    private JsonNode promptsJson;
}
