package app.chatbot.chat;

import app.chatbot.chat.dto.ChatMessageDto;
import app.chatbot.chat.dto.ChatMessageRequest;
import app.chatbot.chat.dto.CompletionParameters;
import app.chatbot.openai.OpenAiResponseParser;
import app.chatbot.openai.OpenAiProxyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatConversationService {

    private final ChatRepository chatRepository;
    private final ChatService chatService;
    private final OpenAiProxyService proxyService;
    private final ChatTitleService chatTitleService;
    private final ObjectMapper objectMapper;

    @Transactional
    public ChatMessageDto generateAssistantMessage(String chatId,
                                                   String model,
                                                   String systemPrompt,
                                                   CompletionParameters parameters) {
        Chat chat = chatRepository.findWithMessagesByChatId(chatId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Chat not found"));

        if (!StringUtils.hasText(model)) {
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "Model must be provided for assistant response");
        }

        String effectiveSystemPrompt = StringUtils.hasText(systemPrompt)
                ? systemPrompt.trim()
                : chat.getSystemPrompt();

        ObjectNode payload = buildPayload(chat, model.trim(), effectiveSystemPrompt, parameters);

        ResponseEntity<String> response = proxyService.createResponse(payload, null);
        if (!response.getStatusCode().is2xxSuccessful()) {
            String message = OpenAiResponseParser.extractErrorMessage(objectMapper, response.getBody());
            throw new ResponseStatusException(BAD_GATEWAY, StringUtils.hasText(message)
                    ? message
                    : "Failed to generate assistant response");
        }

        String responseBody = response.getBody();
        String assistantText;
        try {
            assistantText = OpenAiResponseParser.extractAssistantText(objectMapper, responseBody);
        } catch (IOException exception) {
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "Failed to parse assistant response", exception);
        }

        if (!StringUtils.hasText(assistantText)) {
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "No response text returned from model");
        }

        ChatMessageRequest assistantMessage = new ChatMessageRequest(
                UUID.randomUUID().toString(),
                MessageRole.ASSISTANT,
                assistantText.trim(),
                Instant.now()
        );

        ChatMessageDto responseMessage = chatService.addMessage(chatId, assistantMessage);
        if (!StringUtils.hasText(chat.getTitle())) {
            chatTitleService.generateTitleAsync(chatId, chat.getTitleModel(), chat.getMessages().stream()
                    .map(this::toRequest)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList()));
        }
        log.info("Assistant response stored (chatId={}, messageId={})", chatId, responseMessage.messageId());
        return responseMessage;
    }

    private ObjectNode buildPayload(Chat chat,
                                    String model,
                                    String systemPrompt,
                                    CompletionParameters parameters) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", model);

        if (parameters != null) {
            if (parameters.temperature() != null) {
                payload.put("temperature", parameters.temperature());
            }
            if (parameters.maxTokens() != null) {
                payload.put("max_output_tokens", parameters.maxTokens());
            }
            if (parameters.topP() != null) {
                payload.put("top_p", parameters.topP());
            }
            if (parameters.presencePenalty() != null) {
                payload.put("presence_penalty", parameters.presencePenalty());
            }
            if (parameters.frequencyPenalty() != null) {
                payload.put("frequency_penalty", parameters.frequencyPenalty());
            }
        }

        ArrayNode input = payload.putArray("input");
        if (StringUtils.hasText(systemPrompt)) {
            ObjectNode systemNode = objectMapper.createObjectNode();
            systemNode.put("role", "system");
            systemNode.put("content", systemPrompt.trim());
            input.add(systemNode);
        }

        for (ChatMessage message : chat.getMessages()) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("role", message.getRole().name().toLowerCase());
            node.put("content", message.getContent());
            input.add(node);
        }

        return payload;
    }

    private ChatMessageRequest toRequest(ChatMessage message) {
        if (message == null) {
            return null;
        }
        String messageId = StringUtils.hasText(message.getMessageId())
                ? message.getMessageId()
                : UUID.randomUUID().toString();
        return new ChatMessageRequest(
                messageId,
                message.getRole(),
                message.getContent(),
                message.getCreatedAt()
        );
    }
}
