package app.chatbot.responses.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request DTO für MCP Tool Approval Response.
 * 
 * <p>Frontend sendet dieses Objekt nach User-Approval-Entscheidung.
 * Backend lädt dann Conversation.responseId und sendet Approval-Response an OpenAI.
 * 
 * <p>OpenAI Responses API Format:
 * <pre>{@code
 * POST /responses
 * {
 *   "previous_response_id": "resp_12345",
 *   "model": "gpt-4o",
 *   "modalities": ["text"],
 *   "input": [{
 *     "type": "mcp_approval_response",
 *     "approval_request_id": "apreq_67890",
 *     "approve": true,
 *     "reason": "User confirmed action"
 *   }]
 * }
 * }</pre>
 */
public record ApprovalResponseRequest(
    
    /**
     * Conversation-ID für Response-ID-Lookup.
     */
    @JsonProperty("conversation_id")
    Long conversationId,
    
    /**
     * Approval-Request-ID aus mcp_approval_request Event.
     */
    @JsonProperty("approval_request_id")
    String approvalRequestId,
    
    /**
     * User-Entscheidung: true = approve, false = deny.
     */
    @JsonProperty("approve")
    boolean approve,
    
    /**
     * Optional: Grund für Entscheidung (z.B. "User wants to proceed").
     */
    @JsonProperty("reason")
    String reason
) {}
