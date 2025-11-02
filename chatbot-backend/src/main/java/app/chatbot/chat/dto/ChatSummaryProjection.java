package app.chatbot.chat.dto;

import java.time.Instant;

public record ChatSummaryProjection(
        String chatId,
        String title,
        Instant createdAt,
        Instant updatedAt,
        long messageCount
) {
}

