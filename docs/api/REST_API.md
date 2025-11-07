# REST API Reference

Complete reference for the Chatbot REST API.

## Base URL

```
Development: http://localhost:8080/api
Production: https://api.yourdomain.com/api
```

## Authentication

Currently no authentication is required for local development. For production, implement JWT or OAuth2.

## Conversation Management

### List Conversations

Get all conversations for the user.

```http
GET /api/conversations
```

**Response:**
```json
[
  {
    "id": 1,
    "title": "Weather Discussion",
    "status": "COMPLETED",
    "createdAt": "2025-11-06T10:00:00Z",
    "updatedAt": "2025-11-06T10:05:00Z"
  }
]
```

**Status Codes:**
- `200 OK` - Success
- `500 Internal Server Error` - Server error

### Get Conversation

Get a specific conversation with messages and tool calls.

```http
GET /api/conversations/{id}
```

**Parameters:**
- `id` (path, required) - Conversation ID

**Response:**
```json
{
  "id": 1,
  "title": "Weather Discussion",
  "status": "COMPLETED",
  "createdAt": "2025-11-06T10:00:00Z",
  "updatedAt": "2025-11-06T10:05:00Z",
  "messages": [
    {
      "id": 1,
      "role": "USER",
      "content": "What's the weather in Berlin?",
      "createdAt": "2025-11-06T10:00:00Z",
      "outputIndex": 0
    },
    {
      "id": 2,
      "role": "ASSISTANT",
      "content": "The weather in Berlin is 20°C and sunny.",
      "createdAt": "2025-11-06T10:00:05Z",
      "outputIndex": 0
    }
  ],
  "toolCalls": [
    {
      "id": 1,
      "itemId": "tool_1",
      "type": "MCP",
      "name": "get_weather",
      "status": "COMPLETED",
      "arguments": "{\"location\":\"Berlin\"}",
      "result": "{\"temperature\":20,\"condition\":\"sunny\"}",
      "createdAt": "2025-11-06T10:00:03Z"
    }
  ]
}
```

**Status Codes:**
- `200 OK` - Success
- `404 Not Found` - Conversation not found
- `500 Internal Server Error` - Server error

### Create Conversation

Create a new conversation.

```http
POST /api/conversations
Content-Type: application/json
```

**Request Body:**
```json
{
  "title": "New Chat"
}
```

**Response:**
```json
{
  "id": 2,
  "title": "New Chat",
  "status": "CREATED",
  "createdAt": "2025-11-06T11:00:00Z",
  "updatedAt": "2025-11-06T11:00:00Z"
}
```

**Status Codes:**
- `201 Created` - Conversation created
- `400 Bad Request` - Invalid request body
- `500 Internal Server Error` - Server error

### Delete Conversation

Delete a conversation and all its messages.

```http
DELETE /api/conversations/{id}
```

**Parameters:**
- `id` (path, required) - Conversation ID

**Response:**
```
204 No Content
```

**Status Codes:**
- `204 No Content` - Successfully deleted
- `404 Not Found` - Conversation not found
- `500 Internal Server Error` - Server error

## Streaming Responses

### Stream Chat Response

Stream a chat response using Server-Sent Events (SSE).

```http
POST /api/responses/stream
Content-Type: application/json
Accept: text/event-stream
```

**Request Body:**
```json
{
  "conversationId": 1,
  "payload": {
    "model": "gpt-4",
    "messages": [
      {
        "role": "user",
        "content": "Hello, how are you?"
      }
    ],
    "temperature": 0.7,
    "max_tokens": 1000
  }
}
```

**SSE Events:**

See [SSE Events Reference](./SSE_EVENTS.md) for complete event documentation.

Common events:
- `response.created` - Stream initialized
- `response.text.delta` - Text chunk received
- `approval_required` - Tool approval needed
- `response.completed` - Stream finished
- `response.failed` - Error occurred

**Status Codes:**
- `200 OK` - Stream started
- `400 Bad Request` - Invalid request
- `404 Not Found` - Conversation not found
- `500 Internal Server Error` - Server error

### Approve/Deny Tool Execution

Respond to a tool approval request.

```http
POST /api/responses/approval/{approvalRequestId}
Content-Type: application/json
```

**Parameters:**
- `approvalRequestId` (path, required) - Approval request ID from `approval_required` event

**Request Body:**
```json
{
  "approved": true
}
```

**Response:**
```json
{
  "status": "APPROVED",
  "message": "Tool execution approved"
}
```

**Status Codes:**
- `200 OK` - Approval recorded
- `400 Bad Request` - Invalid request
- `404 Not Found` - Approval request not found
- `408 Request Timeout` - Approval timeout expired
- `500 Internal Server Error` - Server error

## MCP Server Management

### List MCP Servers

Get all configured MCP servers.

```http
GET /api/mcp/servers
```

**Response:**
```json
[
  {
    "id": 1,
    "serverId": "n8n-server-1",
    "name": "n8n Workflows",
    "baseUrl": "http://n8n:5678/api/mcp/sse",
    "transport": "SSE",
    "status": "CONNECTED",
    "lastUpdated": "2025-11-06T10:00:00Z",
    "syncStatus": "SYNCED",
    "lastSyncedAt": "2025-11-06T10:00:00Z"
  }
]
```

**Status Codes:**
- `200 OK` - Success
- `500 Internal Server Error` - Server error

### Get MCP Server

Get a specific MCP server configuration.

```http
GET /api/mcp/servers/{id}
```

**Parameters:**
- `id` (path, required) - Server database ID

**Response:**
```json
{
  "id": 1,
  "serverId": "n8n-server-1",
  "name": "n8n Workflows",
  "baseUrl": "http://n8n:5678/api/mcp/sse",
  "transport": "SSE",
  "status": "CONNECTED",
  "lastUpdated": "2025-11-06T10:00:00Z"
}
```

**Status Codes:**
- `200 OK` - Success
- `404 Not Found` - Server not found
- `500 Internal Server Error` - Server error

### Create/Update MCP Server

Create a new MCP server or update existing one.

```http
POST /api/mcp/servers
Content-Type: application/json
```

**Request Body:**
```json
{
  "serverId": "n8n-server-1",
  "name": "n8n Workflows",
  "baseUrl": "http://n8n:5678/api/mcp/sse",
  "apiKey": "your-api-key",
  "transport": "SSE"
}
```

**Response:**
```json
{
  "id": 1,
  "serverId": "n8n-server-1",
  "name": "n8n Workflows",
  "baseUrl": "http://n8n:5678/api/mcp/sse",
  "transport": "SSE",
  "status": "IDLE",
  "lastUpdated": "2025-11-06T10:00:00Z"
}
```

**Status Codes:**
- `200 OK` - Server updated
- `201 Created` - Server created
- `400 Bad Request` - Invalid request body
- `500 Internal Server Error` - Server error

### Delete MCP Server

Delete an MCP server configuration.

```http
DELETE /api/mcp/servers/{id}
```

**Parameters:**
- `id` (path, required) - Server database ID

**Response:**
```
204 No Content
```

**Status Codes:**
- `204 No Content` - Successfully deleted
- `404 Not Found` - Server not found
- `500 Internal Server Error` - Server error

### Verify MCP Server Connection

Test connection to an MCP server.

```http
POST /api/mcp/servers/{id}/verify
```

**Parameters:**
- `id` (path, required) - Server database ID

**Response:**
```json
{
  "serverId": "n8n-server-1",
  "status": "CONNECTED",
  "message": "Connection successful",
  "capabilities": {
    "toolsCount": 5,
    "resourcesCount": 0,
    "promptsCount": 0
  }
}
```

**Status Codes:**
- `200 OK` - Connection successful
- `400 Bad Request` - Connection failed
- `404 Not Found` - Server not found
- `500 Internal Server Error` - Server error

### Get Server Capabilities

Get tools, resources, and prompts from an MCP server.

```http
GET /api/mcp/servers/{serverId}/capabilities
```

**Parameters:**
- `serverId` (path, required) - Server ID (string, not database ID)

**Response:**
```json
{
  "tools": [
    {
      "name": "get_weather",
      "description": "Fetches weather data for a location",
      "inputSchema": {
        "type": "object",
        "properties": {
          "location": {
            "type": "string",
            "description": "City name"
          }
        },
        "required": ["location"]
      }
    }
  ],
  "resources": [],
  "prompts": [],
  "serverInfo": {
    "name": "n8n MCP Server",
    "version": "1.0"
  }
}
```

**Status Codes:**
- `200 OK` - Success
- `404 Not Found` - Server not found
- `500 Internal Server Error` - Server error

### Sync Server Capabilities

Manually refresh capabilities cache from MCP server.

```http
POST /api/mcp/servers/{serverId}/sync
```

**Parameters:**
- `serverId` (path, required) - Server ID (string)

**Response:**
```json
{
  "serverId": "n8n-server-1",
  "status": "SYNCED",
  "syncedAt": "2025-11-06T10:00:00Z",
  "message": "Sync completed successfully"
}
```

**Status Codes:**
- `200 OK` - Sync successful
- `404 Not Found` - Server not found
- `500 Internal Server Error` - Sync failed

### MCP Server Status Stream (SSE)

Stream real-time status updates for an MCP server.

```http
GET /api/mcp/servers/{serverId}/status/stream
Accept: text/event-stream
```

**Parameters:**
- `serverId` (path, required) - Server ID (string)

**SSE Events:**
```
event: status
data: {"status":"CONNECTING"}

event: status
data: {"status":"CONNECTED"}

event: error
data: {"error":"Connection timeout"}
```

**Status Codes:**
- `200 OK` - Stream started
- `404 Not Found` - Server not found

### Execute Tool

Directly execute an MCP tool (for testing).

```http
POST /api/mcp/tools/execute
Content-Type: application/json
```

**Request Body:**
```json
{
  "serverId": "n8n-server-1",
  "toolName": "get_weather",
  "arguments": {
    "location": "Berlin"
  }
}
```

**Response:**
```json
{
  "content": [
    {
      "type": "text",
      "text": "{\"temperature\":20,\"condition\":\"sunny\"}"
    }
  ],
  "isError": false
}
```

**Status Codes:**
- `200 OK` - Tool executed successfully
- `400 Bad Request` - Invalid tool or arguments
- `404 Not Found` - Server or tool not found
- `500 Internal Server Error` - Execution failed

## Tool Approval Policies

### Get Approval Policies

Get all approval policies for a server.

```http
GET /api/mcp/approval-policies?serverId={serverId}
```

**Query Parameters:**
- `serverId` (optional) - Filter by server ID

**Response:**
```json
[
  {
    "id": 1,
    "serverId": "n8n-server-1",
    "toolName": "dangerous_tool",
    "policy": "ALWAYS_DENY"
  },
  {
    "id": 2,
    "serverId": "n8n-server-1",
    "toolName": "safe_tool",
    "policy": "ALWAYS_ALLOW"
  }
]
```

**Status Codes:**
- `200 OK` - Success
- `500 Internal Server Error` - Server error

### Update Approval Policy

Set or update approval policy for a tool.

```http
PUT /api/mcp/approval-policies
Content-Type: application/json
```

**Request Body:**
```json
{
  "serverId": "n8n-server-1",
  "toolName": "dangerous_tool",
  "policy": "ALWAYS_DENY"
}
```

**Policy Values:**
- `ALWAYS_ALLOW` - Tool executes immediately
- `ALWAYS_DENY` - Tool execution blocked
- `ASK_USER` - User approval required (default)

**Response:**
```json
{
  "id": 1,
  "serverId": "n8n-server-1",
  "toolName": "dangerous_tool",
  "policy": "ALWAYS_DENY"
}
```

**Status Codes:**
- `200 OK` - Policy updated
- `201 Created` - Policy created
- `400 Bad Request` - Invalid request
- `500 Internal Server Error` - Server error

### Delete Approval Policy

Remove approval policy (reverts to default ASK_USER).

```http
DELETE /api/mcp/approval-policies/{id}
```

**Parameters:**
- `id` (path, required) - Policy ID

**Response:**
```
204 No Content
```

**Status Codes:**
- `204 No Content` - Successfully deleted
- `404 Not Found` - Policy not found
- `500 Internal Server Error` - Server error

## Health Check

### Application Health

Get application health status.

```http
GET /actuator/health
```

**Response:**
```json
{
  "status": "UP",
  "components": {
    "db": {
      "status": "UP",
      "details": {
        "database": "PostgreSQL",
        "validationQuery": "isValid()"
      }
    },
    "diskSpace": {
      "status": "UP"
    },
    "ping": {
      "status": "UP"
    }
  }
}
```

**Status Codes:**
- `200 OK` - Application is healthy
- `503 Service Unavailable` - Application is unhealthy

## Error Responses

All error responses follow this format:

```json
{
  "timestamp": "2025-11-06T10:00:00.000+00:00",
  "status": 400,
  "error": "Bad Request",
  "message": "Invalid request parameters",
  "path": "/api/conversations"
}
```

## Rate Limiting

Current implementation has no rate limiting. For production, implement rate limiting on:
- `/api/responses/stream` - 10 requests/minute per IP
- `/api/mcp/tools/execute` - 30 requests/minute per IP
- All other endpoints - 100 requests/minute per IP

## CORS Configuration

**Development:**
```
Allowed Origins: http://localhost:5173, http://localhost:3000
Allowed Methods: GET, POST, PUT, DELETE
Credentials: Allowed
```

**Production:**
Configure allowed origins in `application.properties`:
```properties
cors.allowed-origins=https://yourdomain.com,https://www.yourdomain.com
```

## WebSocket Support

Currently not implemented. Streaming uses SSE which works over HTTP.

## Versioning

API version: **v1**

Future versions will be accessible via:
```
/api/v2/...
```

## SDKs and Client Libraries

### JavaScript/TypeScript

```typescript
import { apiClient } from './services/apiClient';

// List conversations
const conversations = await apiClient.get<Conversation[]>('/conversations');

// Stream chat response
await fetchEventSource('/api/responses/stream', {
  method: 'POST',
  body: JSON.stringify({ conversationId, payload }),
  onmessage: (event) => {
    // Handle event
  }
});
```

### curl Examples

```bash
# Create conversation
curl -X POST http://localhost:8080/api/conversations \
  -H "Content-Type: application/json" \
  -d '{"title":"Test Chat"}'

# Stream response
curl -X POST http://localhost:8080/api/responses/stream \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{
    "conversationId": 1,
    "payload": {
      "model": "gpt-4",
      "messages": [{"role": "user", "content": "Hello"}]
    }
  }'

# List MCP servers
curl http://localhost:8080/api/mcp/servers
```

## See Also

- [SSE Events Reference](./SSE_EVENTS.md) - Detailed streaming events
- [System Architecture](../architecture/SYSTEM_OVERVIEW.md) - Architecture overview
- [Frontend-Backend Communication](../architecture/FRONTEND_BACKEND_COMMUNICATION.md) - Communication patterns
