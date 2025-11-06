package app.chatbot.conversation.dto;

import java.time.Instant;
import java.util.List;

public record ConversationDetailDto(
        Long id,
        String title,
        Instant createdAt,
        Instant updatedAt,
        List<MessageDto> messages,
        List<ToolCallDto> toolCalls
) {}
