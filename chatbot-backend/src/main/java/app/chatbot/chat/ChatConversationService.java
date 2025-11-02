package app.chatbot.chat;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import app.chatbot.chat.dto.ChatMessageDto;
import app.chatbot.chat.dto.ChatMessageRequest;
import app.chatbot.chat.dto.CompletionParameters;
import app.chatbot.chat.dto.ToolCallInfo;
import app.chatbot.mcp.McpClientService;
import app.chatbot.mcp.McpServer;
import app.chatbot.mcp.McpServerStatus;
import app.chatbot.mcp.McpServerRepository;
import app.chatbot.mcp.McpToolContextBuilder;
import app.chatbot.openai.OpenAiProxyService;
import app.chatbot.openai.OpenAiResponseParser;
import app.chatbot.openai.dto.ToolCall;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatConversationService {

    private final ChatRepository chatRepository;
    private final ChatService chatService;
    private final OpenAiProxyService proxyService;
    private final ChatTitleService chatTitleService;
    private final McpToolContextBuilder toolContextBuilder;
    private final McpClientService mcpClientService;
    private final McpServerRepository mcpServerRepository;
    private final ObjectMapper objectMapper;
    
    // Thread-local to track tool calls across the request
    private final ThreadLocal<List<ToolCallInfo>> toolCallTracker = ThreadLocal.withInitial(ArrayList::new);

    @Transactional
    public ChatMessageDto generateAssistantMessage(String chatId,
                                                   String model,
                                                   String systemPrompt,
                                                   CompletionParameters parameters) {
        // Clear and initialize tool call tracker for this request
        toolCallTracker.remove();
        toolCallTracker.get().clear();
        
        try {
            return generateAssistantMessageInternal(chatId, model, systemPrompt, parameters);
        } finally {
            // Clean up thread local
            toolCallTracker.remove();
        }
    }

    private ChatMessageDto generateAssistantMessageInternal(String chatId,
                                                            String model,
                                                            String systemPrompt,
                                                            CompletionParameters parameters) {
        Chat chat = chatRepository.findWithMessagesByChatId(chatId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Chat not found"));

        if (!StringUtils.hasText(model)) {
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "Model must be provided for assistant response");
        }

        String effectiveSystemPrompt = StringUtils.hasText(systemPrompt)
                ? systemPrompt.trim()
                : chat.getSystemPrompt();

        ObjectNode payload = buildPayload(chat, model.trim(), effectiveSystemPrompt, parameters);
        log.info("Sending initial request to LLM (chatId={}, model={}, messages={}, systemPrompt={})", 
                chatId, model.trim(), chat.getMessages().size(), StringUtils.hasText(effectiveSystemPrompt));

        // First request to OpenAI Response API
        ResponseEntity<String> response;
        try {
            response = proxyService.createResponse(payload, null);
        } catch (ResponseStatusException e) {
            log.error("Failed to connect to LLM server (chatId={}, statusCode={}, reason={})", 
                    chatId, e.getStatusCode(), e.getReason(), e);
            throw new ResponseStatusException(BAD_GATEWAY, 
                    "LLM Server nicht erreichbar. Bitte stellen Sie sicher, dass LM Studio läuft (localhost:1234).", e);
        } catch (Exception e) {
            log.error("Unexpected error calling LLM server (chatId={})", chatId, e);
            throw new ResponseStatusException(BAD_GATEWAY, 
                    "Fehler bei LLM-Verbindung: " + e.getMessage(), e);
        }
        
        if (!response.getStatusCode().is2xxSuccessful()) {
            String bodyPreview = response.getBody() != null 
                ? (response.getBody().length() > 200 ? response.getBody().substring(0, 200) + "..." : response.getBody())
                : "empty";
            log.error("OpenAI API returned non-2xx status (chatId={}, status={}, body={})", 
                    chatId, response.getStatusCode(), bodyPreview);
            
            String message = OpenAiResponseParser.extractErrorMessage(objectMapper, response.getBody());
            throw new ResponseStatusException(BAD_GATEWAY, StringUtils.hasText(message)
                    ? message
                    : "LLM Server hat Fehler zurückgegeben. Status: " + response.getStatusCode());
        }

        String responseBody = response.getBody();
        log.debug("Received initial LLM response (chatId={}, bodyLength={})", chatId, 
                responseBody != null ? responseBody.length() : 0);

        // Handle tool calls in a loop - Response API may request multiple rounds
        int maxToolCallRounds = 20; // Allow sufficient rounds for complex tool chains
        int toolCallRound = 0;
        
        while (toolCallRound < maxToolCallRounds) {
            List<ToolCall> toolCalls;
            try {
                toolCalls = OpenAiResponseParser.extractToolCalls(objectMapper, responseBody);
            } catch (IOException exception) {
                log.error("Failed to parse tool calls from response (chatId={}, round={})", 
                        chatId, toolCallRound, exception);
                throw new ResponseStatusException(INTERNAL_SERVER_ERROR, 
                        "Fehler beim Parsen der Tool-Aufrufe", exception);
            }

            // If no more tool calls, break the loop
            if (toolCalls == null || toolCalls.isEmpty()) {
                log.debug("No more tool calls to process (chatId={}, round={})", chatId, toolCallRound);
                break;
            }

            toolCallRound++;
            log.info("Tool call round {}/{}: Processing {} tool calls (chatId={})", 
                toolCallRound, maxToolCallRounds, toolCalls.size(), chatId);
            
            try {
                responseBody = handleToolCalls(payload, responseBody, toolCalls);
            } catch (ResponseStatusException e) {
                log.error("Tool call round {} failed (chatId={}): {}", toolCallRound, chatId, e.getReason(), e);
                throw e;
            } catch (Exception e) {
                log.error("Unexpected error in tool call round {} (chatId={})", toolCallRound, chatId, e);
                throw new ResponseStatusException(INTERNAL_SERVER_ERROR, 
                        "Fehler bei Tool-Ausführung (Runde " + toolCallRound + "): " + e.getMessage(), e);
            }
        }

        if (toolCallRound >= maxToolCallRounds) {
            log.warn("Maximum tool call rounds ({}) reached (chatId={}), stopping to prevent infinite loop", 
                    maxToolCallRounds, chatId);
        }

        // Extract final assistant text
        String assistantText;
        try {
            assistantText = OpenAiResponseParser.extractAssistantText(objectMapper, responseBody);
        } catch (IOException exception) {
            log.error("Failed to parse final assistant response (chatId={})", chatId, exception);
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, 
                    "Fehler beim Extrahieren der Antwort", exception);
        }

        if (!StringUtils.hasText(assistantText)) {
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "No response text returned from model");
        }

        ChatMessageRequest assistantMessage = new ChatMessageRequest(
                UUID.randomUUID().toString(),
                MessageRole.ASSISTANT,
                assistantText.trim(),
                Instant.now()
        );

        // Get collected tool calls
        List<ToolCallInfo> toolCalls = new ArrayList<>(toolCallTracker.get());
        
        ChatMessageDto responseMessage = chatService.addMessage(chatId, assistantMessage);
        
        // Add tool call information to the response
        ChatMessageDto responseWithToolCalls = new ChatMessageDto(
            responseMessage.messageId(),
            responseMessage.role(),
            responseMessage.content(),
            responseMessage.createdAt(),
            toolCalls.isEmpty() ? null : toolCalls
        );
        
        if (!StringUtils.hasText(chat.getTitle())) {
            chatTitleService.generateTitleAsync(chatId, chat.getTitleModel(), chat.getMessages().stream()
                    .map(this::toRequest)
                    .filter(Objects::nonNull)
                    .toList());
        }
        log.info("Assistant response stored (chatId={}, messageId={}, toolCalls={})", 
            chatId, responseWithToolCalls.messageId(), toolCalls.size());
        return responseWithToolCalls;
    }

    private ObjectNode buildPayload(Chat chat,
                                    String model,
                                    String systemPrompt,
                                    CompletionParameters parameters) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", model);

        if (parameters != null) {
            if (parameters.temperature() != null) {
                payload.put("temperature", parameters.temperature());
            }
            if (parameters.maxTokens() != null) {
                payload.put("max_output_tokens", parameters.maxTokens());
            }
            if (parameters.topP() != null) {
                payload.put("top_p", parameters.topP());
            }
            if (parameters.presencePenalty() != null) {
                payload.put("presence_penalty", parameters.presencePenalty());
            }
            if (parameters.frequencyPenalty() != null) {
                payload.put("frequency_penalty", parameters.frequencyPenalty());
            }
        }

        ArrayNode input = payload.putArray("input");
        if (StringUtils.hasText(systemPrompt)) {
            ObjectNode systemNode = objectMapper.createObjectNode();
            systemNode.put("role", "system");
            systemNode.put("content", systemPrompt.trim());
            input.add(systemNode);
        }

        for (ChatMessage message : chat.getMessages()) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("role", message.getRole().name().toLowerCase());
            node.put("content", message.getContent());
            input.add(node);
        }

        // Add MCP tools to the request payload
        toolContextBuilder.augmentPayload(payload);
        log.debug("Payload built with {} input messages and tools array", input.size());

        return payload;
    }

    /**
     * Handles tool calls by routing them to MCP servers and making a second request
     * with the tool results.
     *
     * @param originalPayload Original request payload
     * @param firstResponse   First response body containing tool calls
     * @param toolCalls       List of tool calls to execute
     * @return Final response body after tool execution
     */
    private String handleToolCalls(ObjectNode originalPayload, String firstResponse, List<ToolCall> toolCalls) {
        // Execute all tool calls and collect results
        ArrayNode functionCallOutputs = objectMapper.createArrayNode();

        for (ToolCall toolCall : toolCalls) {
            try {
                String result = executeToolCall(toolCall);
                
                ObjectNode outputNode = objectMapper.createObjectNode();
                outputNode.put("type", "function_call_output");
                outputNode.put("call_id", toolCall.callId());
                outputNode.put("output", result);
                functionCallOutputs.add(outputNode);
                
                log.info("Tool call {} executed successfully", toolCall.name());
            } catch (Exception ex) {
                log.error("Failed to execute tool call {}", toolCall.name(), ex);
                
                // Add error as tool output
                ObjectNode errorNode = objectMapper.createObjectNode();
                errorNode.put("type", "function_call_output");
                errorNode.put("call_id", toolCall.callId());
                errorNode.put("output", "Error: " + ex.getMessage());
                functionCallOutputs.add(errorNode);
            }
        }

        // Build second request with tool results
        try {
            JsonNode firstResponseNode = objectMapper.readTree(firstResponse);
            String previousResponseId = firstResponseNode.has("id") 
                ? firstResponseNode.get("id").asText() 
                : null;

            ObjectNode secondPayload = objectMapper.createObjectNode();
            secondPayload.put("model", originalPayload.get("model").asText());
            
            if (StringUtils.hasText(previousResponseId)) {
                secondPayload.put("previous_response_id", previousResponseId);
            }

            // Add function_call_output items to input array
            ArrayNode input = secondPayload.putArray("input");
            for (JsonNode output : functionCallOutputs) {
                input.add(output);
            }

            // Re-add tools for potential subsequent tool calls
            toolContextBuilder.augmentPayload(secondPayload);

            // Make second request
            ResponseEntity<String> secondResponse = proxyService.createResponse(secondPayload, null);
            if (!secondResponse.getStatusCode().is2xxSuccessful()) {
                String message = OpenAiResponseParser.extractErrorMessage(objectMapper, secondResponse.getBody());
                throw new ResponseStatusException(BAD_GATEWAY, "Failed in second request: " + message);
            }

            return secondResponse.getBody();
        } catch (IOException ex) {
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "Failed to process tool results", ex);
        }
    }

    /**
     * Executes a single tool call by routing to the appropriate MCP server.
     *
     * @param toolCall Tool call to execute
     * @return Tool execution result as JSON string
     */
    private String executeToolCall(ToolCall toolCall) {
        Map<String, Object> arguments = new HashMap<>();
        if (toolCall.arguments() != null && toolCall.arguments().isObject()) {
            toolCall.arguments().fields().forEachRemaining(entry -> arguments.put(entry.getKey(),
                    convertJsonValue(entry.getValue())));
        }

        String argumentsJson = toolCall.arguments() != null ? toolCall.arguments().toString() : "{}";
        log.debug("Executing tool call: name={}, args={}", toolCall.name(), argumentsJson);

        List<McpServer> servers = mcpServerRepository.findAll().stream()
                .filter(server -> server.getStatus() == McpServerStatus.CONNECTED)
                .toList();

        if (servers.isEmpty()) {
            String errorMsg = "Kein verbundener MCP Server verfügbar. Bitte prüfen Sie die MCP-Einstellungen.";
            log.error("Tool '{}' cannot be executed: {}", toolCall.name(), errorMsg);
            toolCallTracker.get().add(new ToolCallInfo(
                    toolCall.name(),
                    "none",
                    argumentsJson,
                    errorMsg,
                    false
            ));
            throw new IllegalStateException(errorMsg);
        }

        Exception lastException = null;
        String lastErrorMessage = null;

        for (McpServer server : servers) {
            try {
                log.debug("Trying tool '{}' on server '{}' ({})",
                        toolCall.name(), server.getServerId(), server.getTransport());

                io.modelcontextprotocol.spec.McpSchema.CallToolResult result =
                        mcpClientService.callTool(server, toolCall.name(), arguments);

                if (result == null) {
                    log.debug("Tool '{}' returned null result on server '{}'",
                            toolCall.name(), server.getServerId());
                    continue;
                }

                if (Boolean.TRUE.equals(result.isError())) {
                    String errorOutput = renderToolResult(result);
                    lastErrorMessage = StringUtils.hasText(errorOutput)
                            ? errorOutput
                            : "Tool execution returned error flag";
                    log.warn("Tool '{}' reported error on server '{}': {}",
                            toolCall.name(), server.getServerId(), lastErrorMessage);
                    lastException = new IllegalStateException(lastErrorMessage);
                    continue;
                }

                String resultString = renderToolResult(result);
                if (StringUtils.hasText(resultString)) {
                    ToolCallInfo info = new ToolCallInfo(
                            toolCall.name(),
                            server.getServerId(),
                            argumentsJson,
                            resultString.length() > 500 ? resultString.substring(0, 500) + "..." : resultString,
                            true
                    );
                    toolCallTracker.get().add(info);
                    log.info("Tool '{}' executed successfully on server '{}' (resultLength={})",
                            toolCall.name(), server.getServerId(), resultString.length());
                    return resultString;
                }

                log.debug("Tool '{}' returned empty result on server '{}'",
                        toolCall.name(), server.getServerId());
                lastErrorMessage = "Tool execution lieferte keine Daten.";
            }
            catch (Exception ex) {
                lastException = ex;
                lastErrorMessage = ex.getMessage();
                log.debug("Tool '{}' failed on server '{}': {}",
                        toolCall.name(), server.getServerId(), ex.getMessage());
            }
        }

        String errorMsg = StringUtils.hasText(lastErrorMessage)
                ? lastErrorMessage
                : (lastException != null ? lastException.getMessage()
                : "Kein MCP Server gefunden für Tool: " + toolCall.name());

        ToolCallInfo failedInfo = new ToolCallInfo(
                toolCall.name(),
                "none",
                argumentsJson,
                errorMsg,
                false
        );
        toolCallTracker.get().add(failedInfo);

        log.error("No MCP server found for tool '{}'. Tried {} servers. Last error: {}",
                toolCall.name(), servers.size(), errorMsg);

        throw new IllegalStateException(errorMsg);
    }

    private String renderToolResult(io.modelcontextprotocol.spec.McpSchema.CallToolResult result) {
        if (result == null) {
            return "";
        }
        if (result.structuredContent() != null) {
            return serializeStructured(result.structuredContent());
        }
        if (result.content() == null || result.content().isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        for (io.modelcontextprotocol.spec.McpSchema.Content content : result.content()) {
            if (content instanceof io.modelcontextprotocol.spec.McpSchema.TextContent textContent) {
                appendWithSeparator(builder, textContent.text());
            }
            else if (content instanceof io.modelcontextprotocol.spec.McpSchema.EmbeddedResource embedded) {
                appendWithSeparator(builder, extractEmbeddedResource(embedded));
            }
            else if (content instanceof io.modelcontextprotocol.spec.McpSchema.ResourceLink link) {
                appendWithSeparator(builder, serializeStructured(link));
            }
            else {
                appendWithSeparator(builder, serializeStructured(content));
            }
        }
        return builder.toString().trim();
    }

    private void appendWithSeparator(StringBuilder builder, String value) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        if (builder.length() > 0) {
            builder.append("\n");
        }
        builder.append(value.trim());
    }

    private String extractEmbeddedResource(io.modelcontextprotocol.spec.McpSchema.EmbeddedResource resource) {
        if (resource == null || resource.resource() == null) {
            return "";
        }
        io.modelcontextprotocol.spec.McpSchema.ResourceContents contents = resource.resource();
        if (contents instanceof io.modelcontextprotocol.spec.McpSchema.TextResourceContents textResource) {
            return textResource.text();
        }
        return serializeStructured(contents);
    }

    private String serializeStructured(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof CharSequence sequence) {
            return sequence.toString();
        }
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException ex) {
            log.debug("Failed to serialize tool result content: {}", ex.getMessage());
            return value.toString();
        }
    }

    private Object convertJsonValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isNumber()) {
            if (node.isIntegralNumber()) {
                return node.asLong();
            }
            return node.asDouble();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isArray()) {
            List<Object> list = new java.util.ArrayList<>();
            node.forEach(item -> list.add(convertJsonValue(item)));
            return list;
        }
        if (node.isObject()) {
            Map<String, Object> map = new HashMap<>();
            node.fields().forEachRemaining(entry -> 
                map.put(entry.getKey(), convertJsonValue(entry.getValue()))
            );
            return map;
        }
        return node.toString();
    }

    private ChatMessageRequest toRequest(ChatMessage message) {
        if (message == null) {
            return null;
        }
        String messageId = StringUtils.hasText(message.getMessageId())
                ? message.getMessageId()
                : UUID.randomUUID().toString();
        return new ChatMessageRequest(
                messageId,
                message.getRole(),
                message.getContent(),
                message.getCreatedAt()
        );
    }
}
