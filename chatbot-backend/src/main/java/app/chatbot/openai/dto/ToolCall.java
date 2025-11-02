package app.chatbot.openai.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Represents a tool call from the OpenAI Response API.
 *
 * @param callId    Unique identifier for this tool call
 * @param name      Name of the tool to call
 * @param arguments Tool arguments as JSON
 */
public record ToolCall(
        String callId,
        String name,
        JsonNode arguments
) {
}
