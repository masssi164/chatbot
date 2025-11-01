package app.chatbot.chat.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.PositiveOrZero;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CompletionParameters(
        @DecimalMin(value = "0.0", inclusive = true, message = "temperature must be >= 0")
        @DecimalMax(value = "2.0", inclusive = true, message = "temperature must be <= 2")
        Double temperature,

        @PositiveOrZero(message = "maxTokens must be >= 0")
        Integer maxTokens,

        @DecimalMin(value = "0.0", inclusive = true, message = "topP must be >= 0")
        @DecimalMax(value = "1.0", inclusive = true, message = "topP must be <= 1")
        Double topP,

        @DecimalMin(value = "-2.0", inclusive = true, message = "presencePenalty must be >= -2")
        @DecimalMax(value = "2.0", inclusive = true, message = "presencePenalty must be <= 2")
        Double presencePenalty,

        @DecimalMin(value = "-2.0", inclusive = true, message = "frequencyPenalty must be >= -2")
        @DecimalMax(value = "2.0", inclusive = true, message = "frequencyPenalty must be <= 2")
        Double frequencyPenalty
) {
        public boolean hasValues() {
                return temperature != null || maxTokens != null || topP != null
                        || presencePenalty != null || frequencyPenalty != null;
        }
}
