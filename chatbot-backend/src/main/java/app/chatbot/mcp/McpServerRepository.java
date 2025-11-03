package app.chatbot.mcp;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository für MCP Server Entities.
 * 
 * Event-Driven Architecture: Keine Pessimistic Locks mehr nötig!
 * ✅ Events werden sequenziell verarbeitet (Spring EventListener)
 * ✅ Idempotente Operationen (connectAndSync checkt selbst ob nötig)
 * ✅ Optimistic Locking nur für schnelle DB-Ops (<100ms)
 */
public interface McpServerRepository extends JpaRepository<McpServer, Long> {
    Optional<McpServer> findByServerId(String serverId);
    boolean existsByServerId(String serverId);
    void deleteByServerId(String serverId);
}
