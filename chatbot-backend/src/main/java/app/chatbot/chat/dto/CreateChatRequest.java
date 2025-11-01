package app.chatbot.chat.dto;

import jakarta.validation.Valid;

import java.util.List;

public record CreateChatRequest(
        String chatId,
        String title,
        String model,
        String systemPrompt,
        String titleModel,
        CompletionParameters parameters,
        @Valid List<ChatMessageRequest> messages
) {
}
