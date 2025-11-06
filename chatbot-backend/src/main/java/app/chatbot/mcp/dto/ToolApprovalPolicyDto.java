package app.chatbot.mcp.dto;

/**
 * Response-DTO für Tool-Approval-Policies.
 * 
 * @param toolName Name des Tools
 * @param policy "always" oder "never"
 */
public record ToolApprovalPolicyDto(
        String toolName,
        String policy
) {
    /**
     * Erstellt DTO aus Entity.
     */
    public static ToolApprovalPolicyDto from(app.chatbot.mcp.ToolApprovalPolicy entity) {
        return new ToolApprovalPolicyDto(
                entity.getToolName(),
                entity.getPolicy()
        );
    }
}
