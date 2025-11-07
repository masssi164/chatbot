package app.chatbot.responses;

import java.time.Duration;

/**
 * Constants for Response Stream Service
 * 
 * Centralizes magic values for better maintainability and configurability.
 */
public final class ResponseStreamConstants {
    
    // Prevent instantiation
    private ResponseStreamConstants() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }
    
    // Timeouts
    public static final Duration DEFAULT_TOOL_TIMEOUT = Duration.ofSeconds(15);
    public static final Duration APPROVAL_WAIT_TIMEOUT = Duration.ofMinutes(5);
    
    // Concurrency
    public static final int MAX_EVENT_CONCURRENCY = 256;
    
    // Event Types - Lifecycle
    public static final String EVENT_RESPONSE_CREATED = "response.created";
    public static final String EVENT_RESPONSE_COMPLETED = "response.completed";
    public static final String EVENT_RESPONSE_INCOMPLETE = "response.incomplete";
    public static final String EVENT_RESPONSE_FAILED = "response.failed";
    
    // Event Types - Errors
    public static final String EVENT_RESPONSE_ERROR = "response.error";
    public static final String EVENT_ERROR = "error";
    
    // Event Types - Text Output
    public static final String EVENT_OUTPUT_TEXT_DELTA = "response.output_text.delta";
    public static final String EVENT_OUTPUT_TEXT_DONE = "response.output_text.done";
    public static final String EVENT_OUTPUT_ITEM_ADDED = "response.output_item.added";
    
    // Event Types - Function Calls
    public static final String EVENT_FUNCTION_ARGUMENTS_DELTA = "response.function_call_arguments.delta";
    public static final String EVENT_FUNCTION_ARGUMENTS_DONE = "response.function_call_arguments.done";
    
    // Event Types - MCP Calls
    public static final String EVENT_MCP_ARGUMENTS_DELTA = "response.mcp_call_arguments.delta";
    public static final String EVENT_MCP_ARGUMENTS_DONE = "response.mcp_call_arguments.done";
    public static final String EVENT_MCP_CALL_IN_PROGRESS = "response.mcp_call.in_progress";
    public static final String EVENT_MCP_CALL_COMPLETED = "response.mcp_call.completed";
    public static final String EVENT_MCP_CALL_FAILED = "response.mcp_call.failed";
    
    // Event Types - MCP Approval
    public static final String EVENT_MCP_APPROVAL_REQUEST = "response.mcp_approval_request";
    
    // Event Types - MCP List Tools
    public static final String EVENT_MCP_LIST_TOOLS_IN_PROGRESS = "response.mcp_list_tools.in_progress";
    public static final String EVENT_MCP_LIST_TOOLS_COMPLETED = "response.mcp_list_tools.completed";
    public static final String EVENT_MCP_LIST_TOOLS_FAILED = "response.mcp_list_tools.failed";
    
    // Item Types
    public static final String ITEM_TYPE_OUTPUT_TEXT = "output_text";
    public static final String ITEM_TYPE_FUNCTION_CALL = "function_call";
    public static final String ITEM_TYPE_MCP_CALL = "mcp_call";
    public static final String ITEM_TYPE_TOOL_OUTPUT = "tool_output";
    
    // MCP Types
    public static final String MCP_TYPE_APPROVAL_RESPONSE = "mcp_approval_response";
    
    // Model Names
    public static final String DEFAULT_MODEL = "gpt-4o";
    
    // Modalities
    public static final String MODALITY_TEXT = "text";
    
    // Custom Events
    public static final String EVENT_CONVERSATION_READY = "conversation.ready";
    public static final String EVENT_APPROVAL_REQUIRED = "approval_required";
}
