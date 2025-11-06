package app.chatbot.conversation;

import java.time.Instant;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final ToolCallRepository toolCallRepository;

    public ConversationService(ConversationRepository conversationRepository,
                               MessageRepository messageRepository,
                               ToolCallRepository toolCallRepository) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.toolCallRepository = toolCallRepository;
    }

    public Mono<Conversation> ensureConversation(Long conversationId, String title) {
        Instant now = Instant.now();
        if (conversationId == null) {
            Conversation conversation = new Conversation();
            conversation.setTitle(normalizeTitle(title));
            conversation.setCreatedAt(now);
            conversation.setUpdatedAt(now);
            return conversationRepository.save(conversation);
        }

        return conversationRepository.findById(conversationId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found")))
                .flatMap(existing -> {
                    String normalized = normalizeTitle(title);
                    if (normalized != null && !normalized.equals(existing.getTitle())) {
                        existing.setTitle(normalized);
                    }
                    existing.setUpdatedAt(now);
                    return conversationRepository.save(existing);
                });
    }

    public Mono<Message> appendMessage(Long conversationId,
                                       MessageRole role,
                                       String content,
                                       String rawJson,
                                       Integer outputIndex,
                                       String itemId) {
        Instant now = Instant.now();
        Message message = new Message();
        message.setConversationId(conversationId);
        message.setRole(role != null ? role : MessageRole.USER);
        message.setContent(content);
        message.setRawJson(rawJson);
        message.setOutputIndex(outputIndex);
        message.setItemId(itemId);
        message.setCreatedAt(now);
        return messageRepository.save(message)
                .flatMap(saved -> touchConversation(conversationId, now).thenReturn(saved));
    }

    public Mono<Message> updateMessageContent(Long conversationId,
                                              String itemId,
                                              String content,
                                              String rawJson,
                                              Integer outputIndex) {
        Instant now = Instant.now();
        return messageRepository.findByConversationIdAndItemId(conversationId, itemId)
                .switchIfEmpty(Mono.defer(() -> appendMessage(conversationId, MessageRole.ASSISTANT, content, rawJson, outputIndex, itemId)))
                .flatMap(existing -> {
                    existing.setContent(content);
                    existing.setRawJson(rawJson);
                    existing.setOutputIndex(outputIndex);
                    return messageRepository.save(existing);
                })
                .flatMap(saved -> touchConversation(conversationId, now).thenReturn(saved));
    }

    public Mono<ToolCall> upsertToolCall(Long conversationId,
                                         String itemId,
                                         ToolCallType type,
                                         Integer outputIndex,
                                         Map<String, Object> attributes) {
        Instant now = Instant.now();
        return toolCallRepository.findByConversationIdAndItemId(conversationId, itemId)
                .switchIfEmpty(Mono.defer(() -> {
                    ToolCall toolCall = new ToolCall();
                    toolCall.setConversationId(conversationId);
                    toolCall.setItemId(itemId);
                    toolCall.setType(type);
                    toolCall.setOutputIndex(outputIndex);
                    toolCall.setCreatedAt(now);
                    toolCall.setStatus(ToolCallStatus.IN_PROGRESS);
                    return Mono.just(toolCall);
                }))
                .flatMap(existing -> applyToolCallAttributes(existing, attributes))
                .flatMap(toolCallRepository::save)
                .onErrorResume(e -> {
                    // Catch both R2DBC and Spring's wrapped DuplicateKeyException
                    if (e instanceof io.r2dbc.spi.R2dbcDataIntegrityViolationException ||
                        e instanceof org.springframework.dao.DuplicateKeyException) {
                        // Race condition: Another thread inserted the same item_id
                        // Retry with UPDATE instead
                        log.debug("Tool call {} already exists (caught {}), retrying with update", 
                                  itemId, e.getClass().getSimpleName());
                        return toolCallRepository.findByConversationIdAndItemId(conversationId, itemId)
                                .flatMap(existing -> applyToolCallAttributes(existing, attributes))
                                .flatMap(toolCallRepository::save);
                    }
                    // Re-throw other exceptions
                    return Mono.error(e);
                })
                .flatMap(saved -> touchConversation(conversationId, now).thenReturn(saved));
    }

    public Flux<Message> listMessages(Long conversationId) {
        return messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
    }

    public Flux<ToolCall> listToolCalls(Long conversationId) {
        return toolCallRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
    }

    private Mono<ToolCall> applyToolCallAttributes(ToolCall toolCall, Map<String, Object> attributes) {
        if (attributes != null && !attributes.isEmpty()) {
            if (attributes.containsKey("name")) {
                toolCall.setName((String) attributes.get("name"));
            }
            if (attributes.containsKey("callId")) {
                toolCall.setCallId((String) attributes.get("callId"));
            }
            if (attributes.containsKey("argumentsJson")) {
                toolCall.setArgumentsJson((String) attributes.get("argumentsJson"));
            }
            if (attributes.containsKey("resultJson")) {
                toolCall.setResultJson((String) attributes.get("resultJson"));
            }
            if (attributes.containsKey("status")) {
                toolCall.setStatus((ToolCallStatus) attributes.get("status"));
            }
            if (attributes.containsKey("outputIndex")) {
                toolCall.setOutputIndex((Integer) attributes.get("outputIndex"));
            }
        }
        if (toolCall.getStatus() == null) {
            toolCall.setStatus(ToolCallStatus.IN_PROGRESS);
        }
        return Mono.just(toolCall);
    }

    private Mono<Void> touchConversation(Long conversationId, Instant timestamp) {
        return conversationRepository.findById(conversationId)
                .flatMap(existing -> {
                    existing.setUpdatedAt(timestamp);
                    return conversationRepository.save(existing);
                })
                .then();
    }

    private String normalizeTitle(String title) {
        if (!StringUtils.hasText(title)) {
            return null;
        }
        String trimmed = title.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Update conversation with response ID from response.created event.
     */
    public Mono<Conversation> updateConversationResponseId(Long conversationId, String responseId) {
        return conversationRepository.findById(conversationId)
                .flatMap(conv -> {
                    conv.setResponseId(responseId);
                    conv.setStatus(ConversationStatus.STREAMING);
                    conv.setUpdatedAt(Instant.now());
                    return conversationRepository.save(conv);
                });
    }

    /**
     * Finalize conversation with status and optional completion reason.
     */
    public Mono<Conversation> finalizeConversation(Long conversationId,
                                                    String responseId,
                                                    ConversationStatus status) {
        return finalizeConversation(conversationId, responseId, status, null);
    }

    /**
     * Finalize conversation with status and completion reason.
     */
    public Mono<Conversation> finalizeConversation(Long conversationId,
                                                    String responseId,
                                                    ConversationStatus status,
                                                    String completionReason) {
        return conversationRepository.findById(conversationId)
                .flatMap(conv -> {
                    if (responseId != null) {
                        conv.setResponseId(responseId);
                    }
                    conv.setStatus(status);
                    conv.setCompletionReason(completionReason);
                    conv.setUpdatedAt(Instant.now());
                    return conversationRepository.save(conv);
                });
    }
}
