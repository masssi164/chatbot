package app.chatbot.mcp;

import java.time.Instant;
import java.util.Map;

import org.springframework.stereotype.Service;

import app.chatbot.mcp.dto.ToolApprovalPolicyDto;
import app.chatbot.mcp.dto.ToolApprovalPolicyRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Service für Tool-Approval-Policy-Management.
 * 
 * <p>Verwaltet User-Präferenzen für MCP-Tool-Ausführungen:
 * <ul>
 *   <li>ALWAYS - User muss Tool bestätigen (Standard)</li>
 *   <li>NEVER - Tool läuft automatisch</li>
 * </ul>
 * 
 * <p>Policies werden vom DefaultToolDefinitionProvider verwendet, um
 * OpenAI Responses API Requests mit korrekten "require_approval"-Werten zu bauen.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ToolApprovalPolicyService {

    private final ToolApprovalPolicyRepository repository;

    /**
     * Gibt Policy für spezifisches Tool zurück.
     * Falls keine Policy gesetzt: Default = NEVER (auto-execute).
     * 
     * @param serverId Server-ID (z.B. "payments-mcp")
     * @param toolName Tool-Name (z.B. "create_payment")
     * @return Mono mit ApprovalPolicy (NEVER falls nicht konfiguriert)
     */
    public Mono<ApprovalPolicy> getPolicyForTool(String serverId, String toolName) {
        return repository.findByServerIdAndToolName(serverId, toolName)
                .map(ToolApprovalPolicy::getPolicyEnum)
                .defaultIfEmpty(ApprovalPolicy.NEVER); // Default: automatisch ausführen
    }

    /**
     * Gibt alle Policies für einen Server zurück als DTOs.
     * Wird vom Frontend verwendet um UI-Toggles zu initialisieren.
     * 
     * @param serverId Server-ID
     * @return Flux aller Policies für diesen Server als DTOs
     */
    public Flux<ToolApprovalPolicyDto> listPoliciesForServer(String serverId) {
        return repository.findByServerId(serverId)
                .map(ToolApprovalPolicyDto::from);
    }

    /**
     * Setzt Policy für einzelnes Tool (mit Request-DTO).
     * 
     * @param serverId Server-ID
     * @param toolName Tool-Name
     * @param request Request mit policy-Wert
     * @return Mono mit DTO der gespeicherten Policy
     */
    public Mono<ToolApprovalPolicyDto> setPolicyForTool(String serverId, String toolName, ToolApprovalPolicyRequest request) {
        ApprovalPolicy policy = request.getPolicyEnum();
        return setPolicyForTool(serverId, toolName, policy)
                .map(ToolApprovalPolicyDto::from);
    }

    /**
     * Setzt Policy für einzelnes Tool (mit Enum).
     * 
     * @param serverId Server-ID
     * @param toolName Tool-Name
     * @param policy ApprovalPolicy Enum
     * @return Mono mit gespeicherter Entity
     */
    private Mono<ToolApprovalPolicy> setPolicyForTool(String serverId, String toolName, ApprovalPolicy policy) {
        log.debug("Setting policy for tool {}.{}: {}", serverId, toolName, policy);
        
        return repository.findByServerIdAndToolName(serverId, toolName)
                .flatMap(existing -> {
                    // Update existing
                    existing.setPolicyEnum(policy);
                    existing.setUpdatedAt(Instant.now());
                    return repository.save(existing);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    // Create new
                    ToolApprovalPolicy newPolicy = ToolApprovalPolicy.builder()
                            .serverId(serverId)
                            .toolName(toolName)
                            .policy(policy.getValue())
                            .createdAt(Instant.now())
                            .updatedAt(Instant.now())
                            .build();
                    return repository.save(newPolicy);
                }))
                .doOnSuccess(saved -> 
                    log.info("✅ Policy saved: {}.{} = {}", serverId, toolName, policy)
                );
    }

    /**
     * Gibt Policies als Map zurück (für schnellen Lookup).
     * Key: toolName, Value: ApprovalPolicy
     * 
     * @param serverId Server-ID
     * @return Mono<Map<toolName, policy>>
     */
    public Mono<Map<String, ApprovalPolicy>> getPoliciesMapForServer(String serverId) {
        return repository.findByServerId(serverId)
                .collectMap(
                    ToolApprovalPolicy::getToolName,
                    ToolApprovalPolicy::getPolicyEnum
                );
    }

    /**
     * Bulk-Update: Setzt Policies für mehrere Tools auf einmal.
     * Nützlich wenn Frontend alle Tool-Toggles auf einmal speichert.
     * 
     * @param serverId Server-ID
     * @param policies Map<toolName, policy>
     * @return Flux mit gespeicherten Policies
     */
    public Flux<ToolApprovalPolicy> bulkUpdatePolicies(String serverId, Map<String, ApprovalPolicy> policies) {
        log.debug("Bulk updating {} policies for server {}", policies.size(), serverId);
        
        return Flux.fromIterable(policies.entrySet())
                .flatMap(entry -> 
                    setPolicyForTool(serverId, entry.getKey(), entry.getValue())
                );
    }

    /**
     * Löscht alle Policies für einen Server.
     * Wird aufgerufen wenn Server gelöscht wird.
     * 
     * @param serverId Server-ID
     * @return Mono<Void> completed when deleted
     */
    public Mono<Void> deletePoliciesForServer(String serverId) {
        log.debug("Deleting all policies for server {}", serverId);
        return repository.deleteByServerId(serverId)
                .doOnSuccess(v -> log.info("✅ Deleted all policies for server {}", serverId));
    }
}
