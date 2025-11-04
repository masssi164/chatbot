package app.chatbot.conversation.dto;

import app.chatbot.conversation.MessageRole;

public record AddMessageRequest(
        MessageRole role,
        String content,
        String rawJson,
        Integer outputIndex,
        String itemId
) {}
