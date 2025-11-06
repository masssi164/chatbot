package app.chatbot.mcp.dto;

import app.chatbot.mcp.ApprovalPolicy;

/**
 * Request-DTO für Tool-Approval-Policy-Updates.
 * 
 * @param toolName Name des Tools
 * @param policy "always" oder "never"
 */
public record ToolApprovalPolicyRequest(
        String toolName,
        String policy
) {
    /**
     * Konvertiert String-Policy zu Enum.
     * 
     * @return ApprovalPolicy enum
     * @throws IllegalArgumentException bei ungültiger Policy
     */
    public ApprovalPolicy getPolicyEnum() {
        return ApprovalPolicy.fromValue(policy);
    }
}
