package app.chatbot.chat.dto;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import app.chatbot.chat.MessageRole;

public record ChatMessageDto(
        String messageId,
        MessageRole role,
        String content,
        Instant createdAt,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        List<ToolCallInfo> toolCalls
) {
    public ChatMessageDto(String messageId, MessageRole role, String content, Instant createdAt) {
        this(messageId, role, content, createdAt, null);
    }
}
