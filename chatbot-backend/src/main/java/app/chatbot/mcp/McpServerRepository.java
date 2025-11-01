package app.chatbot.mcp;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface McpServerRepository extends JpaRepository<McpServer, Long> {
    Optional<McpServer> findByServerId(String serverId);
    boolean existsByServerId(String serverId);
    void deleteByServerId(String serverId);
}

