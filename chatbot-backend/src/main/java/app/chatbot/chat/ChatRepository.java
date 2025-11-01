package app.chatbot.chat;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ChatRepository extends JpaRepository<Chat, Long> {

    boolean existsByChatId(String chatId);

    Optional<Chat> findByChatId(String chatId);

    @EntityGraph(attributePaths = "messages")
    Optional<Chat> findWithMessagesByChatId(String chatId);

    void deleteByChatId(String chatId);
}

