package app.chatbot.conversation.dto;

import java.time.Instant;

import app.chatbot.conversation.MessageRole;

public record NewMessageRequest(
        MessageRole role,
        String content,
        String rawJson,
        Integer outputIndex,
        String itemId,
        Instant createdAt
) {}
