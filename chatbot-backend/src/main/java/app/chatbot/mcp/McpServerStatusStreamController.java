package app.chatbot.mcp;

import java.time.Duration;

import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import app.chatbot.mcp.events.McpServerStatusEvent;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * REST Controller für Server-Sent Events (SSE) Status-Streams.
 * Ermöglicht Frontend Live-Updates während MCP Server-Verbindungen.
 */
@RestController
@RequestMapping("/api/mcp/servers")
@CrossOrigin
@Slf4j
public class McpServerStatusStreamController {

    // Multi-cast Sink: Ein Event geht an alle Subscriber
    private final Sinks.Many<McpServerStatusEvent> statusSink = 
            Sinks.many().multicast().onBackpressureBuffer();

    /**
     * SSE Endpoint für Live-Status-Updates ALLER MCP Server.
     * Empfohlene Methode für Frontend - nur 1 Connection statt N Connections.
     * 
     * Frontend Verwendung:
     * ```javascript
     * const eventSource = new EventSource('/api/mcp/servers/status-stream');
     * eventSource.onmessage = (event) => {
     *   const status = JSON.parse(event.data);
     *   // Update state for server with status.serverId
     * };
     * ```
     * 
     * @return Flux mit Server-Sent Events aller Server
     */
    @GetMapping(value = "/status-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<McpServerStatusEvent>> streamAllServersStatus() {
        log.info("Client connected to global status stream for all servers");
        
        return statusSink.asFlux()
                // Kein .filter() → alle Events werden gestreamt
                .map(event -> ServerSentEvent.<McpServerStatusEvent>builder()
                        .id(String.valueOf(System.currentTimeMillis()))
                        // Per HTML SSE Spezifikation ruft EventSource.onmessage nur Events ohne Custom-Namen ab.
                        // Siehe https://html.spec.whatwg.org/multipage/server-sent-events.html#event-stream-interpretation
                        .data(event)
                        .build())
                .timeout(Duration.ofHours(1)) // Längeres Timeout für globalen Stream
                .doOnCancel(() -> log.info("Client disconnected from global status stream"))
                .doOnComplete(() -> log.info("Global status stream completed"));
    }

    /**
     * SSE Endpoint für Live-Status-Updates eines einzelnen MCP Servers.
     * Für spezielle Fälle - normalerweise /status-stream (global) verwenden.
     * 
     * @param serverId Die Server-ID
     * @return Flux mit Server-Sent Events für einen Server
     */
    @GetMapping(value = "/{serverId}/status-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<McpServerStatusEvent>> streamStatus(@PathVariable String serverId) {
        log.info("Client connected to status stream for server {}", serverId);
        
        return statusSink.asFlux()
                .filter(event -> event.getServerId().equals(serverId))
                .map(event -> ServerSentEvent.<McpServerStatusEvent>builder()
                        .id(String.valueOf(System.currentTimeMillis()))
                        // Gleiches Verhalten wie oben: Default-Eventnamen für maximale Browser-Kompatibilität.
                        .data(event)
                        .build())
                .timeout(Duration.ofMinutes(10)) // Auto-disconnect nach 10 Min ohne Events
                .doOnCancel(() -> log.info("Client disconnected from status stream for server {}", serverId))
                .doOnComplete(() -> log.info("Status stream completed for server {}", serverId));
    }

    /**
     * Event Listener der Status-Events empfängt und an SSE-Stream weiterleitet.
     */
    @EventListener
    public void handleStatusEvent(McpServerStatusEvent event) {
        log.debug("Broadcasting status event for server {} to SSE clients", event.getServerId());
        statusSink.tryEmitNext(event);
    }
}
