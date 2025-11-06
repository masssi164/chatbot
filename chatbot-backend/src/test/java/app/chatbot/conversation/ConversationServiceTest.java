package app.chatbot.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ConversationServiceTest {

    private ConversationRepository conversationRepository;
    private MessageRepository messageRepository;
    private ToolCallRepository toolCallRepository;

    private ConversationService service;

    @BeforeEach
    void setUp() {
        conversationRepository = Mockito.mock(ConversationRepository.class);
        messageRepository = Mockito.mock(MessageRepository.class);
        toolCallRepository = Mockito.mock(ToolCallRepository.class);
        service = new ConversationService(conversationRepository, messageRepository, toolCallRepository);
    }

    @Test
    void ensureConversationCreatesNewWhenIdMissing() {
        Mockito.when(conversationRepository.save(any()))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.ensureConversation(null, "  Hello  "))
                .assertNext(conversation -> {
                    assertThat(conversation.getTitle()).isEqualTo("Hello");
                    assertThat(conversation.getCreatedAt()).isNotNull();
                    assertThat(conversation.getUpdatedAt()).isNotNull();
                })
                .verifyComplete();

        verify(conversationRepository, times(1)).save(any());
    }

    @Test
    void ensureConversationUpdatesExistingWhenFound() {
        Conversation existing = Conversation.builder()
                .id(42L)
                .title("Old")
                .createdAt(Instant.now().minusSeconds(60))
                .updatedAt(Instant.now().minusSeconds(60))
                .build();

        Mockito.when(conversationRepository.findById(42L)).thenReturn(Mono.just(existing));
        Mockito.when(conversationRepository.save(any()))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.ensureConversation(42L, "New Title"))
                .assertNext(conversation -> {
                    assertThat(conversation.getTitle()).isEqualTo("New Title");
                    assertThat(conversation.getUpdatedAt()).isAfter(existing.getCreatedAt());
                })
                .verifyComplete();

        verify(conversationRepository).findById(42L);
        verify(conversationRepository).save(any());
    }

    @Test
    void ensureConversationThrowsWhenMissing() {
        Mockito.when(conversationRepository.findById(7L)).thenReturn(Mono.empty());

        StepVerifier.create(service.ensureConversation(7L, "anything"))
                .expectError(ResponseStatusException.class)
                .verify();
    }

    @Test
    void appendMessageDefaultsRole() {
        Conversation conversation = Conversation.builder().id(10L).build();
        Mockito.when(messageRepository.save(any()))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        Mockito.when(conversationRepository.findById(10L)).thenReturn(Mono.just(conversation));
        Mockito.when(conversationRepository.save(any()))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.appendMessage(10L, null, "Hi", null, 0, "msg-1"))
                .assertNext(message -> {
                    assertThat(message.getRole()).isEqualTo(MessageRole.USER);
                    assertThat(message.getContent()).isEqualTo("Hi");
                })
                .verifyComplete();

        verify(messageRepository).save(any());
        verify(conversationRepository).save(any());
    }

    @Test
    void updateMessageContentCreatesWhenAbsent() {
        Conversation conversation = Conversation.builder().id(11L).build();
        Mockito.when(messageRepository.findByConversationIdAndItemId(11L, "item-a"))
                .thenReturn(Mono.empty());
        Mockito.when(messageRepository.save(any()))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        Mockito.when(conversationRepository.findById(11L)).thenReturn(Mono.just(conversation));
        Mockito.when(conversationRepository.save(any()))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.updateMessageContent(11L, "item-a", "Text", "{}", 3))
                .assertNext(message -> {
                    assertThat(message.getRole()).isEqualTo(MessageRole.ASSISTANT);
                    assertThat(message.getContent()).isEqualTo("Text");
                    assertThat(message.getOutputIndex()).isEqualTo(3);
                })
                .verifyComplete();

    }

    @Test
    void upsertToolCallCreatesAndAppliesAttributes() {
        Conversation conversation = Conversation.builder().id(12L).build();
        Mockito.when(toolCallRepository.findByConversationIdAndItemId(12L, "tool-1"))
                .thenReturn(Mono.empty());
        Mockito.when(toolCallRepository.save(any()))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        Mockito.when(conversationRepository.findById(12L)).thenReturn(Mono.just(conversation));
        Mockito.when(conversationRepository.save(any()))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        Map<String, Object> attributes = Map.of(
                "name", "demo",
                "callId", "call-1",
                "argumentsJson", "{}",
                "status", ToolCallStatus.COMPLETED,
                "resultJson", "{\"result\":true}",
                "outputIndex", 9
        );

        StepVerifier.create(service.upsertToolCall(12L, "tool-1", ToolCallType.MCP, 9, attributes))
                .assertNext(toolCall -> {
                    assertThat(toolCall.getName()).isEqualTo("demo");
                    assertThat(toolCall.getStatus()).isEqualTo(ToolCallStatus.COMPLETED);
                    assertThat(toolCall.getResultJson()).contains("result");
                })
                .verifyComplete();

        verify(toolCallRepository).save(any());
        verify(conversationRepository).save(any());
    }

    @Test
    void upsertToolCallPreservesExisting() {
        Conversation conversation = Conversation.builder().id(13L).build();
        ToolCall existing = ToolCall.builder()
                .conversationId(13L)
                .itemId("tool-2")
                .type(ToolCallType.FUNCTION)
                .status(null)
                .build();

        Mockito.when(toolCallRepository.findByConversationIdAndItemId(13L, "tool-2"))
                .thenReturn(Mono.just(existing));
        Mockito.when(toolCallRepository.save(any()))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        Mockito.when(conversationRepository.findById(13L)).thenReturn(Mono.just(conversation));
        Mockito.when(conversationRepository.save(any()))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.upsertToolCall(13L, "tool-2", ToolCallType.FUNCTION, 1, Map.of()))
                .assertNext(toolCall -> assertThat(toolCall.getStatus()).isEqualTo(ToolCallStatus.IN_PROGRESS))
                .verifyComplete();

        verify(toolCallRepository).save(existing);
    }

    @Test
    void listMessagesDelegates() {
        Mockito.when(messageRepository.findByConversationIdOrderByCreatedAtAsc(99L))
                .thenReturn(Flux.just(Message.builder().id(1L).build()));

        StepVerifier.create(service.listMessages(99L))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    void listToolCallsDelegates() {
        Mockito.when(toolCallRepository.findByConversationIdOrderByCreatedAtAsc(77L))
                .thenReturn(Flux.just(ToolCall.builder().id(2L).build()));

        StepVerifier.create(service.listToolCalls(77L))
                .expectNextCount(1)
                .verifyComplete();
    }
}
