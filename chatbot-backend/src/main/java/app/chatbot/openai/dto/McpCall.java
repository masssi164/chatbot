package app.chatbot.openai.dto;

/**
 * Represents a completed MCP tool call as reported by the Responses API.
 *
 * @param serverLabel the MCP server label
 * @param toolName    the tool name
 * @param arguments   JSON string arguments passed to the tool
 * @param output      tool output (may be JSON or plain text)
 * @param error       error message if the call failed
 */
public record McpCall(
        String serverLabel,
        String toolName,
        String arguments,
        String output,
        String error
) {
}

