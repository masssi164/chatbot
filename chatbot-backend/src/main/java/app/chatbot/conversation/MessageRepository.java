package app.chatbot.conversation;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface MessageRepository extends ReactiveCrudRepository<Message, Long> {

    Flux<Message> findByConversationIdOrderByCreatedAtAsc(Long conversationId);

    Mono<Message> findTopByConversationIdAndOutputIndexOrderByCreatedAtDesc(Long conversationId, Integer outputIndex);

    Mono<Message> findByConversationIdAndItemId(Long conversationId, String itemId);

    Mono<Long> countByConversationId(Long conversationId);
}
