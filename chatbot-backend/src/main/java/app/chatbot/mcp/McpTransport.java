package app.chatbot.mcp;

/**
 * Transport types supported by LiteLLM MCP admin API.
 */
public enum McpTransport {
    SSE,
    STREAMABLE_HTTP;

    /**
     * Lenient factory to support case-insensitive deserialization from JSON.
     */
    @com.fasterxml.jackson.annotation.JsonCreator
    public static McpTransport fromJson(String raw) {
        return fromString(raw);
    }

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
