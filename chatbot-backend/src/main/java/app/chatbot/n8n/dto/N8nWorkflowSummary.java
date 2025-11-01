package app.chatbot.n8n.dto;

import java.time.Instant;
import java.util.List;

public record N8nWorkflowSummary(
        String id,
        String name,
        boolean active,
        Instant updatedAt,
        List<String> tagIds
) {
}
