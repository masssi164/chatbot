package app.chatbot.n8n.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface N8nSettingsRepository extends JpaRepository<N8nSettings, Long> {
}
