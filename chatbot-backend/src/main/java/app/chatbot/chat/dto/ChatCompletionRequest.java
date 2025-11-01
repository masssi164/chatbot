package app.chatbot.chat.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ChatCompletionRequest(
        @NotNull @Valid ChatMessageRequest message,
        @NotBlank String model,
        String systemPrompt,
        @Valid CompletionParameters parameters
) {
}
