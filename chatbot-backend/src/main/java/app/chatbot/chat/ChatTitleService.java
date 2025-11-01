package app.chatbot.chat;

import app.chatbot.chat.dto.ChatMessageRequest;
import app.chatbot.openai.OpenAiProperties;
import app.chatbot.openai.OpenAiProxyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

import app.chatbot.openai.OpenAiResponseParser;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatTitleService {

    private static final String SYSTEM_PROMPT = """
            You generate concise, descriptive chat titles (maximum 6 words). Return only the title text without quotes.
            """;

    private final ChatRepository chatRepository;
    private final OpenAiProxyService proxyService;
    private final ObjectMapper objectMapper;
    private final OpenAiProperties properties;

    @Transactional
    public void generateTitle(String chatId,
                              String preferredModel,
                              List<ChatMessageRequest> submittedMessages) {
        generateTitleInternal(chatId, preferredModel, submittedMessages);
    }

    @Async("titleGenerationExecutor")
    @Transactional
    public void generateTitleAsync(String chatId,
                                   String preferredModel,
                                   List<ChatMessageRequest> submittedMessages) {
        generateTitleInternal(chatId, preferredModel, submittedMessages);
    }

    private void generateTitleInternal(String chatId,
                                       String preferredModel,
                                       List<ChatMessageRequest> submittedMessages) {
        Optional<String> firstUserMessage = extractFirstUserMessage(submittedMessages);
        if (firstUserMessage.isEmpty()) {
            log.debug("Skipping title generation for {}: no user message available", chatId);
            return;
        }

        chatRepository.findByChatId(chatId).ifPresent(chat -> {
            if (StringUtils.hasText(chat.getTitle())) {
                log.debug("Skipping title generation for {}: title already present", chatId);
                return;
            }

            String model = resolveModel(preferredModel);
            String userMessage = firstUserMessage.get();

            Optional<String> generatedTitle = tryGenerateTitleWithResponses(chatId, model, userMessage);

            generatedTitle.ifPresent(title -> {
                chat.setTitle(title);
                chatRepository.save(chat);
                log.info("Generated title for chat {} -> {}", chatId, title);
            });
        });
    }

    private Optional<String> tryGenerateTitleWithResponses(String chatId,
                                                           String model,
                                                           String userMessage) {
        ObjectNode payload = buildResponsePayload(model, userMessage);
        try {
            ResponseEntity<String> response = proxyService.createResponse(payload, null);
            return extractTitle(chatId, response, "responses");
        } catch (Exception exception) {
            log.warn("Title generation via responses API failed for {}", chatId, exception);
            return Optional.empty();
        }
    }

    private Optional<String> extractTitle(String chatId,
                                          ResponseEntity<String> response,
                                          String channel) {
        if (response == null || !response.getStatusCode().is2xxSuccessful() || !StringUtils.hasText(response.getBody())) {
            log.warn("Title generation via {} failed for {} (status={}) message={}",
                    channel,
                    chatId,
                    response != null ? response.getStatusCode() : null,
                    response != null ? OpenAiResponseParser.extractErrorMessage(objectMapper, response.getBody()) : null);
            return Optional.empty();
        }

        try {
            String generatedTitle = OpenAiResponseParser.extractAssistantText(objectMapper, response.getBody());
            String sanitizedTitle = sanitizeTitle(generatedTitle);
            if (!StringUtils.hasText(sanitizedTitle)) {
                log.debug("Generated title empty via {} for {} (raw='{}')", channel, chatId, generatedTitle);
                return Optional.empty();
            }
            return Optional.of(sanitizedTitle);
        } catch (Exception exception) {
            log.warn("Failed to parse title via {} for {}", channel, chatId, exception);
            return Optional.empty();
        }
    }

    private ObjectNode buildResponsePayload(String model, String userMessage) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", model);
        payload.put("temperature", 0.2);
        payload.put("max_output_tokens", 64);

        ArrayNode input = payload.putArray("input");

        ObjectNode system = objectMapper.createObjectNode();
        system.put("role", "system");
        system.put("content", SYSTEM_PROMPT);
        input.add(system);

        ObjectNode user = objectMapper.createObjectNode();
        user.put("role", "user");
        user.put("content", userMessage);
        input.add(user);

        return payload;
    }

    private Optional<String> extractFirstUserMessage(List<ChatMessageRequest> messages) {
        if (messages == null) {
            return Optional.empty();
        }

        return messages.stream()
                .filter(message -> message != null && message.role() == MessageRole.USER)
                .map(ChatMessageRequest::content)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .findFirst();
    }

    private String resolveModel(String preferredModel) {
        if (StringUtils.hasText(preferredModel)) {
            return preferredModel.trim();
        }
        return properties.defaultTitleModel();
    }

    private String sanitizeTitle(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String trimmed = raw.trim();
        String firstLine = trimmed.split("\\R", 2)[0];
        String withoutQuotes = firstLine.replaceAll("^\"+|\"+$", "").trim();
        if (!StringUtils.hasText(withoutQuotes)) {
            return null;
        }
        return withoutQuotes.length() > 80 ? withoutQuotes.substring(0, 77) + "..." : withoutQuotes;
    }
}
