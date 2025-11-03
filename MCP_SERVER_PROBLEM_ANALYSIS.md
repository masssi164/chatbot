# MCP Server Connection Problem - Analyse & Lösung

## 🔴 Problem
Du versuchst, einen n8n **Test-Webhook** als MCP-Server zu registrieren. Das funktioniert nicht!

### Curl Test Ergebnis
```bash
curl -H "Accept: text/event-stream" "http://localhost:5678/mcp-test/2714421f-0865-468b-b938-0d592153a235"
```

**Response:**
```json
{
  "code": 404,
  "message": "The requested webhook \"2714421f-0865-468b-b938-0d592153a235\" is not registered.",
  "hint": "Click the 'Execute workflow' button on the canvas, then try again. (In test mode, the webhook only works for one call after you click this button)"
}
```

## ❌ Was ist falsch?

1. **Test-Webhook**: Diese URL ist ein n8n Test-Webhook, der:
   - Nur EINMAL nach "Execute workflow" Klick funktioniert
   - Nach einem Aufruf wieder deaktiviert wird
   - KEIN persistenter MCP-Server Endpoint ist

2. **Falsche URL-Struktur**: 
   - n8n MCP-Test-Webhooks sind für temporäre Tests gedacht
   - Sie folgen NICHT der MCP-Spezifikation

## ✅ Lösung

### Option 1: Echter n8n MCP-Server (Empfohlen)

Du musst in n8n einen **Production Webhook** erstellen, der als echter MCP-Server fungiert:

1. **Workflow in n8n anpassen**:
   - Webhook-Node auf "Production" setzen (nicht "Test")
   - Workflow speichern und aktivieren (Active = ON)

2. **MCP-Server URL**:
   - Production Webhook URL verwenden (ohne UUID, persistente URL)
   - Beispiel: `http://localhost:5678/webhook/mcp-server`

3. **Transporttyp wählen**:
   - **Streamable HTTP** (modern, empfohlen): Endpoint `/mcp`
   - **SSE** (deprecated): Endpoint `/sse`

### Option 2: Nativer MCP-Server

Wenn n8n keinen echten MCP-Server-Modus hat, musst du einen separaten MCP-Server implementieren:

#### TypeScript MCP-Server (Beispiel)
```typescript
import { Server } from '@modelcontextprotocol/sdk/server/index.js';
import { StreamableHTTPServer } from '@modelcontextprotocol/sdk/server/streamableHttp.js';
import express from 'express';

const app = express();
const mcpServer = new Server({
  name: 'n8n-mcp-bridge',
  version: '1.0.0',
}, {
  capabilities: {
    tools: {},
  },
});

// MCP Tools registrieren, die n8n Workflows aufrufen
mcpServer.setRequestHandler('tools/list', async () => {
  return {
    tools: [
      {
        name: 'trigger_n8n_workflow',
        description: 'Triggers an n8n workflow',
        inputSchema: {
          type: 'object',
          properties: {
            workflowId: { type: 'string' },
            data: { type: 'object' },
          },
        },
      },
    ],
  };
});

const streamableServer = new StreamableHTTPServer(mcpServer);

// MCP Endpoint: POST und GET auf /mcp
app.post('/mcp', (req, res) => streamableServer.handlePostRequest(req, res));
app.get('/mcp', (req, res) => streamableServer.handleGetRequest(req, res));

app.listen(5678, () => console.log('MCP Server running on http://localhost:5678/mcp'));
```

## 📋 MCP-Spezifikation (Wichtig!)

### Streamable HTTP Transport Requirements:

1. **Ein einziger Endpoint** für POST und GET:
   - Beispiel: `http://localhost:5678/mcp`
   
2. **GET Request**: 
   - Öffnet SSE-Stream für Server → Client Messages
   - Header: `Accept: text/event-stream`

3. **POST Request**: 
   - Sendet JSON-RPC Messages an Server
   - Header: `Accept: application/json, text/event-stream`

4. **Initialization**:
   ```json
   POST /mcp
   {
     "jsonrpc": "2.0",
     "id": 1,
     "method": "initialize",
     "params": {
       "protocolVersion": "2024-11-05",
       "clientInfo": {
         "name": "chatbot-backend",
         "version": "1.0.0"
       }
     }
   }
   ```

## 🔧 Backend Code ist KORREKT!

Dein Backend-Code in `McpConnectionService` und `McpEndpointResolver` ist korrekt implementiert:

### ✅ Korrekte Logik:
1. **UUID-Erkennung**: Code erkennt Session-UUIDs und nutzt sie exakt
   ```java
   if (normalizedPath.matches(".*[a-f0-9]{8}-[a-f0-9]{4}...")) {
       return endpoints; // Session URL - use ONLY as provided
   }
   ```

2. **Transport-Defaults**: 
   - SSE: `/sse`
   - Streamable HTTP: `/mcp`
   - Root: `/`

3. **SSE-Handshake**: Prüft auf `data:` Events

## 🎯 Nächste Schritte

### 1. n8n Workflow anpassen
- [ ] Webhook auf "Production" setzen
- [ ] Permanente URL verwenden
- [ ] Workflow aktivieren

### 2. MCP-Server URL korrekt eintragen
- [ ] Production URL verwenden: `http://localhost:5678/webhook/your-endpoint`
- [ ] NICHT die Test-Webhook UUID verwenden

### 3. Transport wählen
- [ ] Streamable HTTP (empfohlen): `http://localhost:5678/mcp`
- [ ] SSE: `http://localhost:5678/sse`

### 4. Optional: Separaten MCP-Server erstellen
- [ ] TypeScript/Node.js MCP-Server implementieren
- [ ] n8n als Backend via REST-API aufrufen
- [ ] MCP-konformen Endpoint bereitstellen

## 📚 Referenzen

- [MCP Specification - Transports](https://modelcontextprotocol.io/docs/concepts/transports)
- [Microsoft Docs - MCP Server](https://learn.microsoft.com/en-us/azure/developer/ai/build-mcp-server-ts)
- [Azure API Management - MCP Servers](https://learn.microsoft.com/en-us/azure/api-management/mcp-server-overview)
