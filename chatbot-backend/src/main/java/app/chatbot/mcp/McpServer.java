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
import jakarta.persistence.Version;
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

    // ===== Optimistic Locking =====
    
    /**
     * Version für optimistic locking.
     * Verhindert lost updates bei concurrent modifications.
     */
    @Version
    private Long version;

    // ===== Capabilities Cache =====
    
    /**
     * Gecachte Tools als JSON Array.
     * Format: [{"name": "tool1", "description": "...", "inputSchema": {...}}, ...]
     */
    @Column(name = "tools_cache", columnDefinition = "TEXT")
    private String toolsCache;

    /**
     * Gecachte Resources als JSON Array.
     * Format: [{"uri": "file://...", "name": "...", "description": "...", "mimeType": "..."}, ...]
     */
    @Column(name = "resources_cache", columnDefinition = "TEXT")
    private String resourcesCache;

    /**
     * Gecachte Prompts als JSON Array.
     * Format: [{"name": "prompt1", "description": "...", "arguments": [...]}, ...]
     */
    @Column(name = "prompts_cache", columnDefinition = "TEXT")
    private String promptsCache;

    // ===== Cache Metadata =====
    
    /**
     * Zeitpunkt der letzten erfolgreichen Synchronisation.
     * Wird gesetzt wenn syncCapabilitiesAsync() erfolgreich abgeschlossen ist.
     */
    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    /**
     * Status der Capabilities-Synchronisation.
     * Steuert Lifecycle des Caches (NEVER_SYNCED → SYNCING → SYNCED/SYNC_FAILED).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "sync_status", nullable = false, length = 20)
    private SyncStatus syncStatus = SyncStatus.NEVER_SYNCED;

    /**
     * Client-Metadaten für Debugging/Monitoring (optional).
     * Format: {"protocolVersion": "2024-11-05", "serverInfo": {...}, "capabilities": {...}}
     */
    @Column(name = "client_metadata", columnDefinition = "TEXT")
    private String clientMetadata;

    // ===== Lifecycle Callbacks =====

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
