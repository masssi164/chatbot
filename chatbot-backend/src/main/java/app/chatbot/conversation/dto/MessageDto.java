package app.chatbot.conversation.dto;

import java.time.Instant;

import app.chatbot.conversation.MessageRole;

public record MessageDto(
        Long id,
        MessageRole role,
        String content,
        String rawJson,
        Integer outputIndex,
        String itemId,
        Instant createdAt
) {}
