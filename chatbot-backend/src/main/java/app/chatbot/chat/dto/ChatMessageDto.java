package app.chatbot.chat.dto;

import app.chatbot.chat.MessageRole;

import java.time.Instant;

public record ChatMessageDto(
        String messageId,
        MessageRole role,
        String content,
        Instant createdAt
) {
}
