package app.chatbot.mcp;

/**
 * Simplified connection status reported by LiteLLM's MCP admin API.
 */
public enum McpServerStatus {
    IDLE,
    CONNECTING,
    CONNECTED,
    ERROR;

    public static McpServerStatus fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            return IDLE;
        }
        try {
            return McpServerStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            // LiteLLM may return custom health strings (e.g. HEALTHY, UNHEALTHY)
            return switch (raw.trim().toLowerCase()) {
                case "healthy", "ok", "success" -> CONNECTED;
                case "connecting", "initializing" -> CONNECTING;
                case "error", "unhealthy", "failed" -> ERROR;
                default -> IDLE;
            };
        }
    }
}
