package app.chatbot.mcp;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Repository für Tool-Approval-Policies.
 * 
 * <p>Ermöglicht CRUD-Operationen auf tool_approval_policies Tabelle.
 */
@Repository
public interface ToolApprovalPolicyRepository extends ReactiveCrudRepository<ToolApprovalPolicy, Long> {

    /**
     * Findet alle Policies für einen MCP-Server.
     * 
     * @param serverId Server-ID (z.B. "payments-mcp")
     * @return Flux aller Policies für diesen Server
     */
    Flux<ToolApprovalPolicy> findByServerId(String serverId);

    /**
     * Findet Policy für spezifisches Tool auf Server.
     * 
     * @param serverId Server-ID
     * @param toolName Tool-Name (z.B. "create_payment")
     * @return Mono mit Policy oder leer falls nicht konfiguriert
     */
    Mono<ToolApprovalPolicy> findByServerIdAndToolName(String serverId, String toolName);

    /**
     * Löscht alle Policies für einen Server.
     * Wird aufgerufen wenn Server gelöscht wird (CASCADE).
     * 
     * @param serverId Server-ID
     * @return Mono<Void> completed when deleted
     */
    Mono<Void> deleteByServerId(String serverId);
}
