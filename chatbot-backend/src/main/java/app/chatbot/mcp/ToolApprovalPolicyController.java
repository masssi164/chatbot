package app.chatbot.mcp;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import app.chatbot.mcp.dto.ToolApprovalPolicyDto;
import app.chatbot.mcp.dto.ToolApprovalPolicyRequest;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * REST-Controller für Tool-Approval-Policy-Management.
 * 
 * <p>Frontend kann damit pro Tool festlegen, ob Approval erforderlich ist:
 * <ul>
 *   <li>GET /api/mcp/servers/{serverId}/tools/approval-policies - Liste aller Policies</li>
 *   <li>PUT /api/mcp/servers/{serverId}/tools/{toolName}/approval-policy - Einzelne Policy setzen</li>
 *   <li>DELETE /api/mcp/servers/{serverId}/tools/approval-policies - Alle Policies löschen</li>
 * </ul>
 */
@RestController
@RequestMapping(path = "/api/mcp/servers", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class ToolApprovalPolicyController {

    private final ToolApprovalPolicyService service;

    /**
     * Liste aller Tool-Approval-Policies für einen Server.
     * 
     * @param serverId Server-ID (z.B. "payments-mcp")
     * @return Flux mit allen konfigurierten Policies
     */
    @GetMapping("/{serverId}/tools/approval-policies")
    public Flux<ToolApprovalPolicyDto> listPolicies(@PathVariable String serverId) {
        return service.listPoliciesForServer(serverId);
    }

    /**
     * Setzt Approval-Policy für einzelnes Tool.
     * 
     * <p>Frontend sendet: {"policy": "always"} oder {"policy": "never"}
     * 
     * @param serverId Server-ID
     * @param toolName Tool-Name
     * @param request Request mit policy-Wert
     * @return Mono mit aktualisierter Policy
     */
    @PutMapping("/{serverId}/tools/{toolName}/approval-policy")
    public Mono<ToolApprovalPolicyDto> setPolicy(
            @PathVariable String serverId,
            @PathVariable String toolName,
            @RequestBody ToolApprovalPolicyRequest request) {
        return service.setPolicyForTool(serverId, toolName, request);
    }

    /**
     * Löscht alle Approval-Policies für einen Server.
     * Nützlich beim Server-Deregistrierung.
     * 
     * @param serverId Server-ID
     * @return Mono<Void>
     */
    @DeleteMapping("/{serverId}/tools/approval-policies")
    public Mono<Void> deletePolicies(@PathVariable String serverId) {
        return service.deletePoliciesForServer(serverId);
    }
}
