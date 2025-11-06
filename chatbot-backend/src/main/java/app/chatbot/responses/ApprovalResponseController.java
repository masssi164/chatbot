package app.chatbot.responses;

import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import app.chatbot.responses.dto.ApprovalResponseRequest;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;

/**
 * REST-Controller für MCP Tool Approval Responses.
 * 
 * <p>Frontend sendet User-Approval-Entscheidung nach mcp_approval_request Event.
 * Controller lädt Conversation.responseId und sendet Approval-Response an OpenAI.
 */
@RestController
@RequestMapping("/api/responses")
@RequiredArgsConstructor
public class ApprovalResponseController {

    private final ResponseStreamService responseStreamService;

    /**
     * POST /api/responses/approval-response
     * 
     * <p>Sendet MCP Approval Response an OpenAI mit previous_response_id.
     * 
     * <p>Request Body:
     * <pre>{@code
     * {
     *   "conversation_id": 123,
     *   "approval_request_id": "apreq_67890",
     *   "approve": true,
     *   "reason": "User confirmed action"
     * }
     * }</pre>
     * 
     * @param request Approval-Response-Request
     * @return Flux mit SSE-Events vom neuen Response-Stream
     */
    @PostMapping(value = "/approval-response", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> sendApprovalResponse(
            @RequestBody ApprovalResponseRequest request) {
        
        return responseStreamService.sendApprovalResponse(
            request.conversationId(),
            request.approvalRequestId(),
            request.approve(),
            request.reason()
        );
    }
}
