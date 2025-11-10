package app.chatbot.mcp.config;

import java.time.Duration;

/**
 * Constants for MCP Session Management
 * 
 * Centralizes configuration values for MCP session lifecycle.
 */
public final class McpSessionConstants {
    
    // Prevent instantiation
    private McpSessionConstants() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }
    
    // Timeouts
    public static final Duration SESSION_IDLE_TIMEOUT = Duration.ofMinutes(30);
    public static final Duration SESSION_INIT_TIMEOUT = Duration.ofSeconds(30);
    public static final Duration SESSION_CLOSE_TIMEOUT = Duration.ofSeconds(5);
    public static final Duration OPERATION_TIMEOUT = Duration.ofSeconds(15);
    
    // Retry Configuration
    public static final Duration RETRY_DELAY = Duration.ofMillis(50);
    public static final int MAX_RETRY_ATTEMPTS = 3;
    
    // Cleanup Configuration
    public static final Duration CLEANUP_INTERVAL = Duration.ofMinutes(10);
}
