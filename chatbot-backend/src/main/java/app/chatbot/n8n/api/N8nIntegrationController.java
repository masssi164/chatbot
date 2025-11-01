package app.chatbot.n8n.api;

import app.chatbot.n8n.N8nClientException;
import app.chatbot.n8n.dto.N8nConnectionRequest;
import app.chatbot.n8n.dto.N8nConnectionResponse;
import app.chatbot.n8n.dto.N8nConnectionStatusResponse;
import app.chatbot.n8n.dto.N8nWorkflowListResponse;
import app.chatbot.n8n.dto.N8nWorkflowSummary;
import app.chatbot.n8n.model.TagDto;
import app.chatbot.n8n.model.WorkflowDto;
import app.chatbot.n8n.model.WorkflowListDto;
import app.chatbot.n8n.service.N8nConnectionService;
import app.chatbot.n8n.service.N8nWorkflowService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@RestController
@RequestMapping("/api/n8n")
public class N8nIntegrationController {

    private final N8nConnectionService connectionService;
    private final N8nWorkflowService workflowService;

    public N8nIntegrationController(N8nConnectionService connectionService, N8nWorkflowService workflowService) {
        this.connectionService = connectionService;
        this.workflowService = workflowService;
    }

    @GetMapping("/connection")
    public N8nConnectionResponse getConnection() {
        return connectionService.currentConnection();
    }

    @PutMapping("/connection")
    public N8nConnectionResponse updateConnection(@Valid @RequestBody N8nConnectionRequest request) {
        try {
            return connectionService.updateConnection(request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @PostMapping("/connection/test")
    public N8nConnectionStatusResponse testConnection() {
        return connectionService.testConnection();
    }

    @GetMapping("/workflows")
    public N8nWorkflowListResponse listWorkflows(
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "active", required = false) Boolean activeFilter
    ) {
        if (!connectionService.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "n8n connection is not configured yet.");
        }
        try {
            WorkflowListDto workflows = workflowService.listWorkflows(
                    activeFilter,
                    null,
                    null,
                    null,
                    null,
                    limit,
                    cursor
            );
            List<N8nWorkflowSummary> summaries = workflows.getData() == null
                    ? List.of()
                    : workflows.getData().stream().map(this::toSummary).toList();
            String nextCursor = null;
            if (workflows.getNextCursor_JsonNullable() != null) {
                var wrapper = workflows.getNextCursor_JsonNullable();
                if (wrapper.isPresent()) {
                    nextCursor = wrapper.orElse(null);
                }
            }
            return new N8nWorkflowListResponse(summaries, nextCursor);
        } catch (N8nClientException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, ex.getMessage(), ex);
        }
    }

    private N8nWorkflowSummary toSummary(WorkflowDto workflow) {
        String id = workflow.getId();
        String name = Optional.ofNullable(workflow.getName()).filter(s -> !s.isBlank()).orElse("Untitled workflow");
        boolean active = Boolean.TRUE.equals(workflow.getActive());
        Instant updatedAt = Optional.ofNullable(workflow.getUpdatedAt())
                .map(OffsetDateTime::toInstant)
                .orElseGet(() -> Optional.ofNullable(workflow.getCreatedAt()).map(OffsetDateTime::toInstant).orElse(null));
        List<String> tagIds = Optional.ofNullable(workflow.getTags())
                .map(tags -> tags.stream()
                        .map(TagDto::getId)
                        .filter(Objects::nonNull)
                        .filter(tag -> !tag.isBlank())
                        .toList())
                .orElse(List.of());
        return new N8nWorkflowSummary(id, name, active, updatedAt, tagIds);
    }
}
