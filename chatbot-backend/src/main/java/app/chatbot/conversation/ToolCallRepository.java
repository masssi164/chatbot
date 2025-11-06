package app.chatbot.conversation;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ToolCallRepository extends ReactiveCrudRepository<ToolCall, Long> {

    Flux<ToolCall> findByConversationIdOrderByCreatedAtAsc(Long conversationId);

    Mono<ToolCall> findTopByConversationIdAndOutputIndexAndTypeOrderByCreatedAtDesc(Long conversationId,
                                                                                    Integer outputIndex,
                                                                                    ToolCallType type);

    Mono<ToolCall> findByConversationIdAndItemId(Long conversationId, String itemId);
}
