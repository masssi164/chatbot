package app.chatbot.mcp.events;

import java.time.Instant;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import app.chatbot.mcp.McpServer;
import app.chatbot.mcp.McpServerStatus;
import app.chatbot.mcp.SyncStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Service zum Publishen von MCP Server Status-Events.
 * Wird von McpServerService verwendet um Frontend über SSE zu informieren.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class McpServerStatusPublisher {

    private final ApplicationEventPublisher eventPublisher;

    /**
     * Published Status-Event für einen Server.
     */
    public void publishStatusUpdate(String serverId, String serverName, 
                                    McpServerStatus connectionStatus, 
                                    SyncStatus syncStatus, String message) {
        McpServerStatusEvent event = McpServerStatusEvent.builder()
                .serverId(serverId)
                .serverName(serverName)
                .connectionStatus(connectionStatus)
                .syncStatus(syncStatus)
                .message(message)
                .timestamp(Instant.now())
                .build();
        
        log.debug("Publishing status event for server {}: {} / {}", serverId, connectionStatus, syncStatus);
        eventPublisher.publishEvent(event);
    }

    /**
     * Published Status-Event mit Capability Counts.
     */
    public void publishStatusWithCounts(McpServer server, String message, 
                                       int toolCount, int resourceCount, int promptCount) {
        McpServerStatusEvent event = McpServerStatusEvent.builder()
                .serverId(server.getServerId())
                .serverName(server.getName())
                .connectionStatus(server.getStatus())
                .syncStatus(server.getSyncStatus())
                .message(message)
                .toolCount(toolCount)
                .resourceCount(resourceCount)
                .promptCount(promptCount)
                .timestamp(Instant.now())
                .build();
        
        log.debug("Publishing status event for server {}: {} tools, {} resources, {} prompts", 
                server.getServerId(), toolCount, resourceCount, promptCount);
        eventPublisher.publishEvent(event);
    }

    /**
     * Published vollständiges Status-Event mit Capabilities JSON.
     * Nur bei SYNCED Events verwenden (enthält volle Capabilities).
     */
    public void publishStatusWithCapabilities(McpServer server, String message,
                                             JsonNode toolsJson, JsonNode resourcesJson, JsonNode promptsJson) {
        int toolCount = toolsJson != null ? toolsJson.size() : 0;
        int resourceCount = resourcesJson != null ? resourcesJson.size() : 0;
        int promptCount = promptsJson != null ? promptsJson.size() : 0;
        
        McpServerStatusEvent event = McpServerStatusEvent.builder()
                .serverId(server.getServerId())
                .serverName(server.getName())
                .connectionStatus(server.getStatus())
                .syncStatus(server.getSyncStatus())
                .message(message)
                .toolCount(toolCount)
                .resourceCount(resourceCount)
                .promptCount(promptCount)
                .toolsJson(toolsJson)
                .resourcesJson(resourcesJson)
                .promptsJson(promptsJson)
                .timestamp(Instant.now())
                .build();
        
        log.debug("Publishing status event with full capabilities for server {}: {} tools, {} resources, {} prompts", 
                server.getServerId(), toolCount, resourceCount, promptCount);
        eventPublisher.publishEvent(event);
    }
}
