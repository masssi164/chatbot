package app.chatbot.mcp.events;

import java.time.Instant;

/**
 * Event das gefeuert wird wenn ein MCP-Server erstellt oder aktualisiert wurde.
 * 
 * Trigger: REST API Calls (POST, PUT)
 * Listener: McpConnectionEventListener → startet async Connection
 * 
 * Event-Driven Pattern: Entkoppelt Request-Handling von Connection-Processing
 * ✅ Keine Race Conditions mehr
 * ✅ Sequential Processing pro Server
 * ✅ Einfaches Modell ohne Locks oder Deduplication
 */
public record McpServerUpdatedEvent(
    String serverId,
    Instant timestamp
) {
    public McpServerUpdatedEvent(String serverId) {
        this(serverId, Instant.now());
    }
}
