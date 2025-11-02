package app.chatbot.mcp;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "mcp_servers")
@Getter
@Setter
@NoArgsConstructor
public class McpServer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Setter(AccessLevel.NONE)
    private Long id;

    @Column(name = "server_id", nullable = false, unique = true, length = 64)
    private String serverId;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(nullable = false, length = 512)
    private String baseUrl;

    @Column(length = 1024)
    private String apiKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private McpServerStatus status = McpServerStatus.IDLE;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private McpTransport transport = McpTransport.STREAMABLE_HTTP;

    @Column(nullable = false)
    private Instant lastUpdated;

    @PrePersist
    void onCreate() {
        if (lastUpdated == null) {
            lastUpdated = Instant.now();
        }
    }

    @PreUpdate
    void onUpdate() {
        lastUpdated = Instant.now();
    }
}
