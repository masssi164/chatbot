package app.chatbot.mcp;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Reactive Repository für MCP Server Entities mit R2DBC.
 * 
 * ✅ Fully reactive - alle Methoden geben Mono/Flux zurück
 * ✅ Non-blocking I/O für bessere Skalierung mit SSE/Streaming
 * ✅ Kompatibel mit McpAsyncClient (bereits reactive)
 */
public interface McpServerRepository extends ReactiveCrudRepository<McpServer, Long> {
    Mono<McpServer> findByServerId(String serverId);
    Mono<Boolean> existsByServerId(String serverId);
    Mono<Void> deleteByServerId(String serverId);
    
    // Alle Server sortiert nach Name
    Flux<McpServer> findAllByOrderByNameAsc();
}
