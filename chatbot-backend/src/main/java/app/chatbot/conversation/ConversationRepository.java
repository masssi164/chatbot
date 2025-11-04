package app.chatbot.conversation;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface ConversationRepository extends ReactiveCrudRepository<Conversation, Long> {

    Flux<Conversation> findAllByOrderByUpdatedAtDesc();
}
