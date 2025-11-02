package app.chatbot.chat.dto;

import java.util.List;

/**
 * Response containing assistant message with tool call metadata.
 *
 * @param message    The final assistant message
 * @param toolCalls  List of tool calls that were executed
 */
public record ChatGenerationResponse(
        ChatMessageDto message,
        List<ToolCallInfo> toolCalls
) {
}
