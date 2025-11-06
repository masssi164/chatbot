package app.chatbot.mcp;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Persistiert Tool-Approval-Policies für MCP-Server-Tools.
 * 
 * <p>Jedes Tool eines MCP-Servers kann individuell konfiguriert werden:
 * <ul>
 *   <li>ALWAYS - User muss bestätigen (Standard für sensible Tools)</li>
 *   <li>NEVER - Läuft automatisch (für read-only/sichere Tools)</li>
 * </ul>
 * 
 * <p>Beispiel:
 * <pre>
 * Server: "payments-mcp"
 *   - create_payment: ALWAYS (sensibel)
 *   - get_invoice: NEVER (read-only)
 *   - list_transactions: ALWAYS (default)
 * </pre>
 * 
 * <p>Die Policy wird vom Backend beim Aufbau der OpenAI Responses API Request
 * verwendet, um Tools nach Approval-Anforderung zu gruppieren.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("tool_approval_policies")
public class ToolApprovalPolicy {

    @Id
    private Long id;

    /**
     * MCP Server ID (Foreign Key zu mcp_servers.server_id).
     */
    @Column("server_id")
    private String serverId;

    /**
     * Name des Tools (z.B. "create_payment", "get_weather").
     * Muss mit Tool-Namen aus tools_cache übereinstimmen.
     */
    @Column("tool_name")
    private String toolName;

    /**
     * Approval-Policy als String (für R2DBC).
     * Werte: "always" oder "never"
     */
    private String policy;

    @Column("created_at")
    private Instant createdAt;

    @Column("updated_at")
    private Instant updatedAt;

    // ===== Enum-Helper =====

    /**
     * Get policy as enum.
     * 
     * @return ApprovalPolicy.ALWAYS or NEVER
     */
    public ApprovalPolicy getPolicyEnum() {
        return ApprovalPolicy.fromValue(policy);
    }

    /**
     * Set policy from enum.
     * 
     * @param policy ApprovalPolicy enum value
     */
    public void setPolicyEnum(ApprovalPolicy policy) {
        this.policy = policy != null ? policy.getValue() : ApprovalPolicy.ALWAYS.getValue();
    }
}
