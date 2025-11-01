package app.chatbot.chat.dto;

import java.time.Instant;
import java.util.List;

public record ChatDto(
        String chatId,
        String title,
        String systemPrompt,
        String titleModel,
        Instant createdAt,
        Instant updatedAt,
        List<ChatMessageDto> messages
) {
}
