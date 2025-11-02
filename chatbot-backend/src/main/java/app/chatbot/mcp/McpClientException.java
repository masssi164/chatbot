package app.chatbot.mcp;

/**
 * Exception für Fehler bei MCP-Client-Operationen.
 */
public class McpClientException extends RuntimeException {

    public McpClientException(String message) {
        super(message);
    }

    public McpClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
