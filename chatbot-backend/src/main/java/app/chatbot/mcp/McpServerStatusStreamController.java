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
            Sinks.many().replay().limit(1);

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
        Flux<ServerSentEvent<McpServerStatusEvent>> statusUpdates = statusSink.asFlux()
                .map(event -> ServerSentEvent.<McpServerStatusEvent>builder()
                        .id(String.valueOf(System.currentTimeMillis()))
                        .data(event)
                        .build());

        Flux<ServerSentEvent<McpServerStatusEvent>> heartbeat = Flux.interval(Duration.ofSeconds(15))
                .map(tick -> ServerSentEvent.<McpServerStatusEvent>builder()
                        .event("heartbeat")
                        .comment("keep-alive")
                        .build());

        return Flux.merge(statusUpdates, heartbeat)
                .doOnSubscribe(subscription -> log.info("Client connected to global status stream for all servers"))
                .doOnCancel(() -> log.info("Client disconnected from global status stream"))
                .doOnError(error -> log.warn("Global status stream error", error))
                .doFinally(signalType -> log.debug("Global status stream terminated ({})", signalType));
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
                .mergeWith(Flux.interval(Duration.ofSeconds(15))
                        .map(tick -> ServerSentEvent.<McpServerStatusEvent>builder()
                                .event("heartbeat")
                                .comment("keep-alive")
                                .build()))
                .doOnCancel(() -> log.info("Client disconnected from status stream for server {}", serverId))
                .doOnError(error -> log.warn("Status stream error for server {}", serverId, error))
                .doFinally(signal -> log.debug("Status stream terminated for server {} ({})", serverId, signal));
    }

    /**
     * Event Listener der Status-Events empfängt und an SSE-Stream weiterleitet.
     */
    @EventListener
    public void handleStatusEvent(McpServerStatusEvent event) {
        log.debug("Broadcasting status event for server {} to SSE clients", event.getServerId());
        try {
            statusSink.emitNext(event, Sinks.EmitFailureHandler.FAIL_FAST);
        } catch (Sinks.EmissionException emissionException) {
            log.warn("Failed to emit status event for server {}: {}", event.getServerId(), emissionException.getMessage());
        }
    }
}
