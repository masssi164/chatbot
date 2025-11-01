package app.chatbot.chat.dto;

import app.chatbot.chat.MessageRole;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record ChatMessageRequest(
        @NotBlank String messageId,
        @NotNull MessageRole role,
        @NotBlank String content,
        Instant createdAt
) {
}
