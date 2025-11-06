package app.chatbot.conversation;

import java.util.Collections;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import app.chatbot.conversation.dto.AddMessageRequest;
import app.chatbot.conversation.dto.ConversationDetailDto;
import app.chatbot.conversation.dto.ConversationSummaryDto;
import app.chatbot.conversation.dto.CreateConversationRequest;
import app.chatbot.conversation.dto.MessageDto;
import app.chatbot.conversation.dto.NewMessageRequest;
import app.chatbot.conversation.dto.ToolCallDto;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping(path = "/api/conversations", produces = MediaType.APPLICATION_JSON_VALUE)
public class ConversationController {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final ToolCallRepository toolCallRepository;
    private final ConversationService conversationService;

    public ConversationController(ConversationRepository conversationRepository,
                                  MessageRepository messageRepository,
                                  ToolCallRepository toolCallRepository,
                                  ConversationService conversationService) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.toolCallRepository = toolCallRepository;
        this.conversationService = conversationService;
    }

    @GetMapping
    public Flux<ConversationSummaryDto> listConversations() {
        return conversationRepository.findAllByOrderByUpdatedAtDesc()
                .flatMap(conversation -> messageRepository.countByConversationId(conversation.getId())
                        .map(count -> new ConversationSummaryDto(
                                conversation.getId(),
                                conversation.getTitle(),
                                conversation.getCreatedAt(),
                                conversation.getUpdatedAt(),
                                count
                        )));
    }

    @GetMapping("/{id}")
    public Mono<ConversationDetailDto> getConversation(@PathVariable("id") Long conversationId) {
        return conversationRepository.findById(conversationId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found")))
                .flatMap(conversation -> Mono.zip(
                        messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId)
                                .map(this::toMessageDto)
                                .collectList(),
                        toolCallRepository.findByConversationIdOrderByCreatedAtAsc(conversationId)
                                .map(this::toToolCallDto)
                                .collectList()
                ).map(tuple -> new ConversationDetailDto(
                        conversation.getId(),
                        conversation.getTitle(),
                        conversation.getCreatedAt(),
                        conversation.getUpdatedAt(),
                        tuple.getT1(),
                        tuple.getT2()
                )));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ConversationDetailDto> createConversation(@RequestBody CreateConversationRequest request) {
        return conversationService.ensureConversation(null, request.title())
                .flatMap(conversation -> {
                    List<NewMessageRequest> messages = request.messages() != null
                            ? request.messages()
                            : Collections.emptyList();

                    return Flux.fromIterable(messages)
                            .concatMap(msg -> conversationService.appendMessage(
                                    conversation.getId(),
                                    msg.role(),
                                    msg.content(),
                                    msg.rawJson(),
                                    msg.outputIndex(),
                                    msg.itemId()
                            ))
                            .then(getConversation(conversation.getId()));
                });
    }

    @PostMapping(path = "/{id}/messages", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<MessageDto> addMessage(@PathVariable("id") Long conversationId,
                                       @RequestBody AddMessageRequest request) {
        return conversationRepository.findById(conversationId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found")))
                .flatMap(conversation -> conversationService.appendMessage(
                                conversation.getId(),
                                request.role(),
                                request.content(),
                                request.rawJson(),
                                request.outputIndex(),
                                request.itemId()
                        )
                        .map(this::toMessageDto));
    }

    private MessageDto toMessageDto(Message message) {
        return new MessageDto(
                message.getId(),
                message.getRole(),
                message.getContent(),
                message.getRawJson(),
                message.getOutputIndex(),
                message.getItemId(),
                message.getCreatedAt()
        );
    }

    private ToolCallDto toToolCallDto(ToolCall toolCall) {
        return new ToolCallDto(
                toolCall.getId(),
                toolCall.getType(),
                toolCall.getName(),
                toolCall.getCallId(),
                toolCall.getArgumentsJson(),
                toolCall.getResultJson(),
                toolCall.getStatus(),
                toolCall.getOutputIndex(),
                toolCall.getItemId(),
                toolCall.getCreatedAt()
        );
    }
}
