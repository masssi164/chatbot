package app.chatbot.mcp.dto;

import java.time.Instant;

import app.chatbot.mcp.SyncStatus;

/**
 * DTO für Sync-Status Response.
 * Wird von POST /api/mcp-servers/{serverId}/sync zurückgegeben.
 */
public record SyncStatusDto(
    String serverId,
    SyncStatus status,
    Instant syncedAt,
    String message
) {}
