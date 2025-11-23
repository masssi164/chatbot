Feature: Chatbot end-to-end connectivity

  Background:
    Given the stack is healthy

  Scenario: Stream a chat response through the UI
    When I open the chatbot UI
    And I submit a chat message "Hello from the E2E suite"
    Then I see an assistant reply

  Scenario: Register the default n8n MCP server
    When I upsert the default n8n MCP server
    Then LiteLLM lists the server via the backend
