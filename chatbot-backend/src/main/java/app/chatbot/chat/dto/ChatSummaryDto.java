package app.chatbot.chat.dto;

import java.time.Instant;

public record ChatSummaryDto(
        String chatId,
        String title,
        Instant createdAt,
        Instant updatedAt,
        int messageCount
) {
}
