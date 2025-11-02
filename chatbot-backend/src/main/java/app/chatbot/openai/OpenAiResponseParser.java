package app.chatbot.openai;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.util.StringUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import app.chatbot.openai.dto.ToolCall;

public final class OpenAiResponseParser {

    private OpenAiResponseParser() {
    }

    public static String extractAssistantText(ObjectMapper objectMapper, String body) throws IOException {
        if (!StringUtils.hasText(body)) {
            return "";
        }

        JsonNode root = objectMapper.readTree(body);
        if (root.hasNonNull("output_text")) {
            return root.get("output_text").asText("");
        }

        StringBuilder builder = new StringBuilder();

        JsonNode output = root.get("output");
        if (output != null && output.isArray()) {
            for (JsonNode item : output) {
                if (!item.hasNonNull("role") || !"assistant".equalsIgnoreCase(item.get("role").asText())) {
                    continue;
                }
                appendContent(item.get("content"), builder);
            }
            if (!builder.isEmpty()) {
                return builder.toString();
            }
        }

        JsonNode choices = root.get("choices");
        if (choices != null && choices.isArray()) {
            for (JsonNode choice : choices) {
                JsonNode message = choice.get("message");
                if (message == null) {
                    continue;
                }
                appendContent(message.get("content"), builder);
            }
            if (!builder.isEmpty()) {
                return builder.toString();
            }
        }

        return "";
    }

    public static String extractErrorMessage(ObjectMapper objectMapper, String body) {
        if (!StringUtils.hasText(body)) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode error = root.get("error");
            if (error != null) {
                if (error.hasNonNull("message")) {
                    return error.get("message").asText("");
                }
                if (error.hasNonNull("code")) {
                    return error.get("code").asText("");
                }
            }
        } catch (IOException ignored) {
            // fall through to body string
        }
        return body;
    }

    private static void appendContent(JsonNode contentNode, StringBuilder builder) {
        if (contentNode == null || contentNode.isNull()) {
            return;
        }

        if (contentNode.isTextual()) {
            appendText(contentNode.asText(), builder);
            return;
        }

        if (contentNode.isArray()) {
            for (JsonNode entry : contentNode) {
                if (entry == null || entry.isNull()) {
                    continue;
                }
                if (entry.hasNonNull("text")) {
                    appendText(entry.get("text").asText(), builder);
                } else if (entry.isTextual()) {
                    appendText(entry.asText(), builder);
                }
            }
            return;
        }

        if (contentNode.hasNonNull("text")) {
            appendText(contentNode.get("text").asText(), builder);
        }
    }

    private static void appendText(String text, StringBuilder builder) {
        if (!StringUtils.hasText(text)) {
            return;
        }
        String trimmed = text.trim();
        if (!StringUtils.hasText(trimmed)) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append("\n\n");
        }
        builder.append(trimmed);
    }

    /**
     * Extracts tool calls from the Response API output array.
     * Response API returns tool calls with type="function_call" in the output array.
     *
     * @param objectMapper Jackson ObjectMapper
     * @param body         Response body from OpenAI Response API
     * @return List of ToolCall objects
     * @throws IOException if JSON parsing fails
     */
    public static List<ToolCall> extractToolCalls(ObjectMapper objectMapper, String body) throws IOException {
        List<ToolCall> toolCalls = new ArrayList<>();

        if (!StringUtils.hasText(body)) {
            return toolCalls;
        }

        JsonNode root = objectMapper.readTree(body);
        JsonNode output = root.get("output");

        if (output == null || !output.isArray()) {
            return toolCalls;
        }

        for (JsonNode item : output) {
            if (item == null || !item.hasNonNull("type")) {
                continue;
            }

            String type = item.get("type").asText("");
            if (!"function_call".equalsIgnoreCase(type)) {
                continue;
            }

            String callId = item.has("call_id") ? item.get("call_id").asText(null) : null;
            String name = item.has("name") ? item.get("name").asText(null) : null;
            JsonNode arguments = item.has("arguments") ? item.get("arguments") : null;

            if (StringUtils.hasText(callId) && StringUtils.hasText(name)) {
                toolCalls.add(new ToolCall(callId, name, arguments));
            }
        }

        return toolCalls;
    }
}
