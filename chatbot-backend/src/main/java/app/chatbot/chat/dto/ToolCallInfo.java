package app.chatbot.chat.dto;

/**
 * Information about a tool call that was executed.
 *
 * @param toolName  Name of the tool that was called
 * @param server    MCP server that executed the tool
 * @param arguments Tool arguments (JSON string)
 * @param result    Tool execution result
 * @param success   Whether the tool call succeeded
 */
public record ToolCallInfo(
        String toolName,
        String server,
        String arguments,
        String result,
        boolean success
) {
}
