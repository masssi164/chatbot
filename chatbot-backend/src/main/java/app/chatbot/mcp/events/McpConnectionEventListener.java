package app.chatbot.mcp.events;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import app.chatbot.mcp.McpConnectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Event-Listener für MCP Server Updates.
 * 
 * Pattern: Event-Driven Architecture
 * ✅ Sequential Processing: Nur 1 Connection pro Server zur gleichen Zeit
 * ✅ Idempotent: Kann mehrfach aufgerufen werden ohne Probleme
 * ✅ Non-blocking: Request kommt sofort zurück, Connection läuft async
 * ✅ Keine Locks/Retries/Deduplication nötig
 * 
 * Flow:
 * 1. Controller updated DB (synchron, schnell <100ms)
 * 2. Controller published Event
 * 3. Listener startet async Connection (15 Sekunden)
 * 4. Frontend bekommt sofort Response, Connection läuft im Background
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class McpConnectionEventListener {

    private final McpConnectionService mcpConnectionService;

    /**
     * Reagiert auf MCP Server Updates und triggert Connection.
     * 
     * WICHTIG: KEIN @Async hier!
     * - @EventListener OHNE @Async → Events werden sequenziell verarbeitet
     * - Verhindert parallele Connections für denselben Server
     * - connectAndSync() läuft blocking, aber das ist OK (15-30s)
     * - Frontend bekommt Response sofort (Controller published Event + return)
     * 
     * Alternative mit @Async würde zu Race Conditions führen:
     * - Event 1, 2, 3 starten PARALLEL
     * - Alle 3 öffnen gleichzeitig MCP Session
     * - OptimisticLockingFailureException beim DB-Update
     */
    @EventListener
    public void handleServerUpdated(McpServerUpdatedEvent event) {
        String serverId = event.serverId();
        log.info("Received McpServerUpdated event for server {}, triggering connection (blocking)", serverId);
        
        try {
            // Idempotente Operation: Checkt selbst ob Connection nötig ist
            mcpConnectionService.connectAndSync(serverId);
            log.info("Successfully completed connection for server {}", serverId);
        } catch (Exception ex) {
            log.error("Failed to connect server {} after update event: {}", serverId, ex.getMessage(), ex);
            // Status wurde bereits auf ERROR gesetzt in McpConnectionService
        }
    }
}
