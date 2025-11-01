package app.chatbot.n8n.dto;

import java.util.List;

public record N8nWorkflowListResponse(
        List<N8nWorkflowSummary> items,
        String nextCursor
) {
}
