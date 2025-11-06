package app.chatbot.conversation.dto;

import java.time.Instant;

import app.chatbot.conversation.ToolCallStatus;
import app.chatbot.conversation.ToolCallType;

public record ToolCallDto(
        Long id,
        ToolCallType type,
        String name,
        String callId,
        String argumentsJson,
        String resultJson,
        ToolCallStatus status,
        Integer outputIndex,
        String itemId,
        Instant createdAt
) {}
