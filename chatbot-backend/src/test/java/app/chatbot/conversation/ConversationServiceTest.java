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

    @Test
    void updateMessageContentUpdatesExistingMessage() {
        Conversation conversation = Conversation.builder().id(20L).build();
        Message existing = Message.builder()
                .id(201L)
                .conversationId(20L)
                .itemId("item-x")
                .role(MessageRole.ASSISTANT)
                .content("Old")
                .build();

        Mockito.when(messageRepository.findByConversationIdAndItemId(20L, "item-x"))
                .thenReturn(Mono.just(existing));
        Mockito.when(messageRepository.save(any()))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        Mockito.when(conversationRepository.findById(20L)).thenReturn(Mono.just(conversation));
        Mockito.when(conversationRepository.save(any()))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.updateMessageContent(20L, "item-x", "New content", "{}", 5))
                .assertNext(message -> {
                    assertThat(message.getContent()).isEqualTo("New content");
                    assertThat(message.getOutputIndex()).isEqualTo(5);
                })
                .verifyComplete();

        verify(messageRepository).save(any());
    }

    @Test
    void updateConversationResponseIdSetsResponseIdAndStatus() {
        Conversation conversation = Conversation.builder()
                .id(30L)
                .title("Test")
                .build();

        Mockito.when(conversationRepository.findById(30L)).thenReturn(Mono.just(conversation));
        Mockito.when(conversationRepository.save(any()))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.updateConversationResponseId(30L, "resp-123"))
                .assertNext(conv -> {
                    assertThat(conv.getResponseId()).isEqualTo("resp-123");
                    assertThat(conv.getStatus()).isEqualTo(ConversationStatus.STREAMING);
                })
                .verifyComplete();

        verify(conversationRepository).save(any());
    }

    @Test
    void finalizeConversationWithoutReasonSetsStatus() {
        Conversation conversation = Conversation.builder()
                .id(40L)
                .title("Test")
                .build();

        Mockito.when(conversationRepository.findById(40L)).thenReturn(Mono.just(conversation));
        Mockito.when(conversationRepository.save(any()))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.finalizeConversation(40L, "resp-456", ConversationStatus.COMPLETED))
                .assertNext(conv -> {
                    assertThat(conv.getResponseId()).isEqualTo("resp-456");
                    assertThat(conv.getStatus()).isEqualTo(ConversationStatus.COMPLETED);
                    assertThat(conv.getCompletionReason()).isNull();
                })
                .verifyComplete();

        verify(conversationRepository).save(any());
    }

    @Test
    void finalizeConversationWithReasonSetsStatusAndReason() {
        Conversation conversation = Conversation.builder()
                .id(50L)
                .title("Test")
                .build();

        Mockito.when(conversationRepository.findById(50L)).thenReturn(Mono.just(conversation));
        Mockito.when(conversationRepository.save(any()))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.finalizeConversation(50L, "resp-789", ConversationStatus.INCOMPLETE, "timeout"))
                .assertNext(conv -> {
                    assertThat(conv.getResponseId()).isEqualTo("resp-789");
                    assertThat(conv.getStatus()).isEqualTo(ConversationStatus.INCOMPLETE);
                    assertThat(conv.getCompletionReason()).isEqualTo("timeout");
                })
                .verifyComplete();

        verify(conversationRepository).save(any());
    }

    @Test
    void finalizeConversationWithoutResponseIdPreservesExisting() {
        Conversation conversation = Conversation.builder()
                .id(60L)
                .title("Test")
                .responseId("existing-resp")
                .build();

        Mockito.when(conversationRepository.findById(60L)).thenReturn(Mono.just(conversation));
        Mockito.when(conversationRepository.save(any()))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.finalizeConversation(60L, null, ConversationStatus.COMPLETED, null))
                .assertNext(conv -> {
                    assertThat(conv.getResponseId()).isEqualTo("existing-resp");
                    assertThat(conv.getStatus()).isEqualTo(ConversationStatus.COMPLETED);
                })
                .verifyComplete();
    }

    @Test
    void upsertToolCallHandlesDuplicateKeyException() {
        Conversation conversation = Conversation.builder().id(70L).build();
        ToolCall existingToolCall = ToolCall.builder()
                .id(701L)
                .conversationId(70L)
                .itemId("tool-dup")
                .type(ToolCallType.MCP)
                .status(ToolCallStatus.IN_PROGRESS)
                .build();

        Mockito.when(toolCallRepository.findByConversationIdAndItemId(70L, "tool-dup"))
                .thenReturn(Mono.empty())
                .thenReturn(Mono.just(existingToolCall));
        Mockito.when(toolCallRepository.save(any()))
                .thenReturn(Mono.error(new org.springframework.dao.DuplicateKeyException("Duplicate key")))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        Mockito.when(conversationRepository.findById(70L)).thenReturn(Mono.just(conversation));
        Mockito.when(conversationRepository.save(any()))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.upsertToolCall(70L, "tool-dup", ToolCallType.MCP, 1, Map.of()))
                .expectNextMatches(toolCall -> toolCall.getItemId().equals("tool-dup"))
                .verifyComplete();

        verify(toolCallRepository, times(2)).findByConversationIdAndItemId(70L, "tool-dup");
        verify(toolCallRepository, times(2)).save(any());
    }

    @Test
    void upsertToolCallHandlesR2dbcDataIntegrityViolationException() {
        Conversation conversation = Conversation.builder().id(80L).build();
        ToolCall existingToolCall = ToolCall.builder()
                .id(801L)
                .conversationId(80L)
                .itemId("tool-r2dbc")
                .type(ToolCallType.FUNCTION)
                .status(ToolCallStatus.IN_PROGRESS)
                .build();

        Mockito.when(toolCallRepository.findByConversationIdAndItemId(80L, "tool-r2dbc"))
                .thenReturn(Mono.empty())
                .thenReturn(Mono.just(existingToolCall));
        Mockito.when(toolCallRepository.save(any()))
                .thenReturn(Mono.error(new io.r2dbc.spi.R2dbcDataIntegrityViolationException("Constraint violation")))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        Mockito.when(conversationRepository.findById(80L)).thenReturn(Mono.just(conversation));
        Mockito.when(conversationRepository.save(any()))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.upsertToolCall(80L, "tool-r2dbc", ToolCallType.FUNCTION, 2, Map.of()))
                .expectNextMatches(toolCall -> toolCall.getItemId().equals("tool-r2dbc"))
                .verifyComplete();

        verify(toolCallRepository, times(2)).findByConversationIdAndItemId(80L, "tool-r2dbc");
    }

    @Test
    void upsertToolCallRethrowsOtherExceptions() {
        Conversation conversation = Conversation.builder().id(90L).build();

        Mockito.when(toolCallRepository.findByConversationIdAndItemId(90L, "tool-error"))
                .thenReturn(Mono.empty());
        Mockito.when(toolCallRepository.save(any()))
                .thenReturn(Mono.error(new RuntimeException("Unexpected error")));
        Mockito.when(conversationRepository.findById(90L)).thenReturn(Mono.just(conversation));

        StepVerifier.create(service.upsertToolCall(90L, "tool-error", ToolCallType.MCP, 3, Map.of()))
                .expectError(RuntimeException.class)
                .verify();
    }

    @Test
    void applyToolCallAttributesHandlesNullAttributes() {
        Conversation conversation = Conversation.builder().id(100L).build();
        Mockito.when(toolCallRepository.findByConversationIdAndItemId(100L, "tool-null"))
                .thenReturn(Mono.empty());
        Mockito.when(toolCallRepository.save(any()))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        Mockito.when(conversationRepository.findById(100L)).thenReturn(Mono.just(conversation));
        Mockito.when(conversationRepository.save(any()))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.upsertToolCall(100L, "tool-null", ToolCallType.MCP, 0, null))
                .assertNext(toolCall -> {
                    assertThat(toolCall.getStatus()).isEqualTo(ToolCallStatus.IN_PROGRESS);
                })
                .verifyComplete();
    }

    @Test
    void applyToolCallAttributesHandlesEmptyAttributes() {
        Conversation conversation = Conversation.builder().id(110L).build();
        Mockito.when(toolCallRepository.findByConversationIdAndItemId(110L, "tool-empty"))
                .thenReturn(Mono.empty());
        Mockito.when(toolCallRepository.save(any()))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        Mockito.when(conversationRepository.findById(110L)).thenReturn(Mono.just(conversation));
        Mockito.when(conversationRepository.save(any()))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.upsertToolCall(110L, "tool-empty", ToolCallType.FUNCTION, 0, Map.of()))
                .assertNext(toolCall -> {
                    assertThat(toolCall.getStatus()).isEqualTo(ToolCallStatus.IN_PROGRESS);
                })
                .verifyComplete();
    }

    @Test
    void ensureConversationDoesNotUpdateTitleWhenNull() {
        Conversation existing = Conversation.builder()
                .id(120L)
                .title("Original")
                .createdAt(Instant.now().minusSeconds(60))
                .updatedAt(Instant.now().minusSeconds(60))
                .build();

        Mockito.when(conversationRepository.findById(120L)).thenReturn(Mono.just(existing));
        Mockito.when(conversationRepository.save(any()))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.ensureConversation(120L, null))
                .assertNext(conversation -> {
                    assertThat(conversation.getTitle()).isEqualTo("Original");
                })
                .verifyComplete();
    }

    @Test
    void ensureConversationDoesNotUpdateTitleWhenSame() {
        Conversation existing = Conversation.builder()
                .id(130L)
                .title("Same Title")
                .createdAt(Instant.now().minusSeconds(60))
                .updatedAt(Instant.now().minusSeconds(60))
                .build();

        Mockito.when(conversationRepository.findById(130L)).thenReturn(Mono.just(existing));
        Mockito.when(conversationRepository.save(any()))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.ensureConversation(130L, "Same Title"))
                .assertNext(conversation -> {
                    assertThat(conversation.getTitle()).isEqualTo("Same Title");
                })
                .verifyComplete();
    }

    @Test
    void ensureConversationHandlesWhitespaceTitle() {
        Mockito.when(conversationRepository.save(any()))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.ensureConversation(null, "   "))
                .assertNext(conversation -> {
                    assertThat(conversation.getTitle()).isNull();
                })
                .verifyComplete();
    }

    @Test
    void ensureConversationHandlesEmptyStringTitle() {
        Mockito.when(conversationRepository.save(any()))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.ensureConversation(null, ""))
                .assertNext(conversation -> {
                    assertThat(conversation.getTitle()).isNull();
                })
                .verifyComplete();
    }
}
