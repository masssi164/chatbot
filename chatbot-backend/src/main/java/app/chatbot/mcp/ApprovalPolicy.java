package app.chatbot.mcp;

/**
 * Tool approval policy für MCP Tools.
 * 
 * <p>Bestimmt ob ein Tool vor Ausführung User-Freigabe benötigt:
 * <ul>
 *   <li>ALWAYS - User muss Tool-Ausführung bestätigen (Standard für sensible Operations)</li>
 *   <li>NEVER - Tool läuft automatisch ohne Nachfrage (für read-only/sichere Tools)</li>
 * </ul>
 * 
 * <p>Wird in OpenAI Responses API als "require_approval" übertragen.
 * 
 * @see <a href="https://platform.openai.com/docs/guides/tools-connectors-mcp">OpenAI MCP Tools</a>
 */
public enum ApprovalPolicy {
    /**
     * User muss Tool-Ausführung explizit bestätigen.
     * Backend sendet mcp_approval_request Event ans Frontend.
     */
    ALWAYS("always"),
    
    /**
     * Tool läuft automatisch ohne User-Interaktion.
     * Keine Approval-Dialoge im Frontend.
     */
    NEVER("never");

    private final String value;

    ApprovalPolicy(String value) {
        this.value = value;
    }

    /**
     * Returns the policy value for OpenAI Responses API.
     * 
     * @return "always" or "never"
     */
    public String getValue() {
        return value;
    }

    /**
     * Parse policy from string value.
     * 
     * @param value "always" or "never"
     * @return matching ApprovalPolicy
     * @throws IllegalArgumentException if value is invalid
     */
    public static ApprovalPolicy fromValue(String value) {
        if (value == null) {
            return ALWAYS; // Default: immer fragen (sicher)
        }
        
        for (ApprovalPolicy policy : values()) {
            if (policy.value.equalsIgnoreCase(value)) {
                return policy;
            }
        }
        
        throw new IllegalArgumentException("Invalid approval policy: " + value);
    }
}
