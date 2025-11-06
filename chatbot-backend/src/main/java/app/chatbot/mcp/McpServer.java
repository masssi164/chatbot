package app.chatbot.mcp;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("mcp_servers")
public class McpServer {

    @Id
    private Long id;

    @Column("server_id")
    private String serverId;

    private String name;

    @Column("base_url")
    private String baseUrl;

    @Column("api_key")
    private String apiKey;

    // Store enums as strings for R2DBC
    private String status;
    
    private String transport;

    @Column("last_updated")
    private Instant lastUpdated;

    // ===== Optimistic Locking =====
    
    /**
     * Version für optimistic locking.
     * ⚠️ R2DBC has no @Version annotation - must be managed manually in service layer
     */
    private Long version;

    // ===== Capabilities Cache =====
    
    /**
     * Gecachte Tools als JSON Array.
     * Format: [{"name": "tool1", "description": "...", "inputSchema": {...}}, ...]
     */
    @Column("tools_cache")
    private String toolsCache;

    /**
     * Gecachte Resources als JSON Array.
     * Format: [{"uri": "file://...", "name": "...", "description": "...", "mimeType": "..."}, ...]
     */
    @Column("resources_cache")
    private String resourcesCache;

    /**
     * Gecachte Prompts als JSON Array.
     * Format: [{"name": "prompt1", "description": "...", "arguments": [...]}, ...]
     */
    @Column("prompts_cache")
    private String promptsCache;

    // ===== Cache Metadata =====
    
    /**
     * Zeitpunkt der letzten erfolgreichen Synchronisation.
     * Wird gesetzt wenn syncCapabilitiesAsync() erfolgreich abgeschlossen ist.
     */
    @Column("last_synced_at")
    private Instant lastSyncedAt;

    /**
     * Status der Capabilities-Synchronisation.
     * Steuert Lifecycle des Caches (NEVER_SYNCED → SYNCING → SYNCED/SYNC_FAILED).
     * Stored as string for R2DBC compatibility.
     */
    @Column("sync_status")
    private String syncStatus;

    /**
     * Client-Metadaten für Debugging/Monitoring (optional).
     * Format: {"protocolVersion": "2024-11-05", "serverInfo": {...}, "capabilities": {...}}
     */
    @Column("client_metadata")
    private String clientMetadata;

    // ===== Helper methods for enum conversion =====

    public McpServerStatus getStatusEnum() {
        return status != null ? McpServerStatus.valueOf(status) : McpServerStatus.IDLE;
    }

    public void setStatusEnum(McpServerStatus status) {
        this.status = status != null ? status.name() : McpServerStatus.IDLE.name();
    }

    public McpTransport getTransportEnum() {
        return transport != null ? McpTransport.valueOf(transport) : McpTransport.STREAMABLE_HTTP;
    }

    public void setTransportEnum(McpTransport transport) {
        this.transport = transport != null ? transport.name() : McpTransport.STREAMABLE_HTTP.name();
    }

    public SyncStatus getSyncStatusEnum() {
        return syncStatus != null ? SyncStatus.valueOf(syncStatus) : SyncStatus.NEVER_SYNCED;
    }

    public void setSyncStatusEnum(SyncStatus syncStatus) {
        this.syncStatus = syncStatus != null ? syncStatus.name() : SyncStatus.NEVER_SYNCED.name();
    }
}
