package app.chatbot.n8n.service;

import app.chatbot.n8n.N8nClientException;
import app.chatbot.n8n.api.ExecutionApi;
import app.chatbot.n8n.api.WorkflowApi;
import app.chatbot.n8n.model.ExecutionDto;
import app.chatbot.n8n.model.ExecutionListDto;
import app.chatbot.n8n.model.TagDto;
import app.chatbot.n8n.model.TagIdsInner;
import app.chatbot.n8n.model.WorkflowDto;
import app.chatbot.n8n.model.WorkflowListDto;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class N8nWorkflowService {

    private final WorkflowApi workflowApi;
    private final ExecutionApi executionApi;
    public N8nWorkflowService(WorkflowApi workflowApi, ExecutionApi executionApi) {
        this.workflowApi = workflowApi;
        this.executionApi = executionApi;
    }

    public WorkflowListDto listWorkflows(Boolean active, String tags, String name, String projectId,
                                         Boolean excludePinnedData, Integer limit, String cursor) {
        try {
            BigDecimal limitValue = limit != null ? BigDecimal.valueOf(limit) : null;
            return workflowApi.workflowsGet(active, tags, name, projectId, excludePinnedData, limitValue, cursor);
        } catch (RestClientResponseException ex) {
            throw responseFailure("retrieve workflows", ex);
        } catch (RestClientException ex) {
            throw requestFailure("retrieve workflows", ex);
        }
    }

    public WorkflowDto getWorkflow(String workflowId, Boolean excludePinnedData) {
        try {
            return workflowApi.workflowsIdGet(workflowId, excludePinnedData);
        } catch (RestClientResponseException ex) {
            throw responseFailure("load workflow %s".formatted(workflowId), ex);
        } catch (RestClientException ex) {
            throw requestFailure("load workflow %s".formatted(workflowId), ex);
        }
    }

    public WorkflowDto activateWorkflow(String workflowId) {
        try {
            return workflowApi.workflowsIdActivatePost(workflowId);
        } catch (RestClientResponseException ex) {
            throw responseFailure("activate workflow %s".formatted(workflowId), ex);
        } catch (RestClientException ex) {
            throw requestFailure("activate workflow %s".formatted(workflowId), ex);
        }
    }

    public WorkflowDto deactivateWorkflow(String workflowId) {
        try {
            return workflowApi.workflowsIdDeactivatePost(workflowId);
        } catch (RestClientResponseException ex) {
            throw responseFailure("deactivate workflow %s".formatted(workflowId), ex);
        } catch (RestClientException ex) {
            throw requestFailure("deactivate workflow %s".formatted(workflowId), ex);
        }
    }

    public List<TagDto> getWorkflowTags(String workflowId) {
        try {
            List<TagDto> response = workflowApi.workflowsIdTagsGet(workflowId);
            return Optional.ofNullable(response).orElse(List.of());
        } catch (RestClientResponseException ex) {
            throw responseFailure("load tags for workflow %s".formatted(workflowId), ex);
        } catch (RestClientException ex) {
            throw requestFailure("load tags for workflow %s".formatted(workflowId), ex);
        }
    }

    public List<TagDto> updateWorkflowTags(String workflowId, List<String> tagIds) {
        try {
            List<TagIdsInner> request = CollectionUtils.isEmpty(tagIds)
                    ? List.of()
                    : tagIds.stream()
                            .map(id -> new TagIdsInner().id(id))
                            .collect(Collectors.toList());
            List<TagDto> response = workflowApi.workflowsIdTagsPut(workflowId, request);
            return Optional.ofNullable(response).orElse(List.of());
        } catch (RestClientResponseException ex) {
            throw responseFailure("update tags for workflow %s".formatted(workflowId), ex);
        } catch (RestClientException ex) {
            throw requestFailure("update tags for workflow %s".formatted(workflowId), ex);
        }
    }

    public ExecutionListDto listExecutions(Boolean includeData, String status, String workflowId,
                                           String projectId, Integer limit, String cursor) {
        try {
            BigDecimal limitValue = limit != null ? BigDecimal.valueOf(limit) : null;
            return executionApi.executionsGet(includeData, status, workflowId, projectId, limitValue, cursor);
        } catch (RestClientResponseException ex) {
            throw responseFailure("list executions", ex);
        } catch (RestClientException ex) {
            throw requestFailure("list executions", ex);
        }
    }

    public ExecutionDto getExecution(long executionId, Boolean includeData) {
        try {
            return executionApi.executionsIdGet(BigDecimal.valueOf(executionId), includeData);
        } catch (RestClientResponseException ex) {
            throw responseFailure("load execution %d".formatted(executionId), ex);
        } catch (RestClientException ex) {
            throw requestFailure("load execution %d".formatted(executionId), ex);
        }
    }

    public ExecutionDto retryExecution(long executionId) {
        try {
            return executionApi.executionsIdRetryPost(BigDecimal.valueOf(executionId), null);
        } catch (RestClientResponseException ex) {
            throw responseFailure("retry execution %d".formatted(executionId), ex);
        } catch (RestClientException ex) {
            throw requestFailure("retry execution %d".formatted(executionId), ex);
        }
    }

    private N8nClientException responseFailure(String action, RestClientResponseException ex) {
        String body = ex.getResponseBodyAsString();
        String trimmed = body != null ? body.trim() : "";
        if (trimmed.length() > 512) {
            trimmed = trimmed.substring(0, 512) + "…";
        }
        String detail = trimmed.isEmpty() ? "" : ": " + trimmed;
        String message = "n8n API call to %s failed with %d %s%s".formatted(
                action,
                ex.getStatusCode().value(),
                ex.getStatusText(),
                detail
        );
        return new N8nClientException(message, ex);
    }

    private N8nClientException requestFailure(String action, RestClientException ex) {
        return new N8nClientException("Failed to %s: %s".formatted(action, ex.getMessage()), ex);
    }
}
