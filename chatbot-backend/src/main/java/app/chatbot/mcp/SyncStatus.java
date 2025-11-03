package app.chatbot.mcp;

/**
 * Status der Capabilities-Synchronisation eines MCP Servers.
 * Steuert den Lifecycle des Capability-Caches (Tools, Resources, Prompts).
 */
public enum SyncStatus {
    /**
     * Server wurde noch nie synchronisiert.
     * Capabilities-Cache ist leer.
     */
    NEVER_SYNCED,
    
    /**
     * Synchronisation läuft aktuell.
     * Wird gesetzt wenn syncCapabilitiesAsync() aufgerufen wird.
     */
    SYNCING,
    
    /**
     * Letzte Synchronisation war erfolgreich.
     * Cache enthält gültige Daten (lastSyncedAt != null).
     */
    SYNCED,
    
    /**
     * Letzte Synchronisation ist fehlgeschlagen.
     * Cache könnte veraltet oder leer sein.
     */
    SYNC_FAILED
}
