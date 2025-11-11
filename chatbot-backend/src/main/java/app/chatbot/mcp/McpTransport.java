package app.chatbot.mcp;

/**
 * Transport types supported by LiteLLM MCP admin API.
 */
public enum McpTransport {
    SSE,
    STREAMABLE_HTTP;

    public static McpTransport fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            return STREAMABLE_HTTP;
        }
        try {
            return McpTransport.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return STREAMABLE_HTTP;
        }
    }
}
