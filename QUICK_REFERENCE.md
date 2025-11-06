# Quick Reference Guide

## 📖 Documentation Index

### Main Documentation
- **[AGENTS.md](./AGENTS.md)** - Root level overview, architecture, business logic
- **[CODE_QUALITY_REVIEW.md](./CODE_QUALITY_REVIEW.md)** - Code quality findings and improvement recommendations

### Backend Documentation
- **[chatbot-backend/AGENTS.md](./chatbot-backend/AGENTS.md)** - Backend architecture overview
- **[chatbot-backend/src/main/java/app/chatbot/mcp/AGENTS.md](./chatbot-backend/src/main/java/app/chatbot/mcp/AGENTS.md)** - MCP integration details
- **[chatbot-backend/src/main/java/app/chatbot/responses/AGENTS.md](./chatbot-backend/src/main/java/app/chatbot/responses/AGENTS.md)** - OpenAI streaming details

### Frontend Documentation
- **[chatbot/AGENTS.md](./chatbot/AGENTS.md)** - Frontend architecture overview
- **[chatbot/src/store/AGENTS.md](./chatbot/src/store/AGENTS.md)** - Zustand store details

### Existing Guides
- **[REQUIREMENTS.md](./REQUIREMENTS.md)** - MCP async migration requirements
- **[DEPLOYMENT_GUIDE.md](./DEPLOYMENT_GUIDE.md)** - Production deployment
- **[DOCKER_COMPOSE_GUIDE.md](./DOCKER_COMPOSE_GUIDE.md)** - Docker setup
- **[OLLAMA_LITELLM_GUIDE.md](./OLLAMA_LITELLM_GUIDE.md)** - LLM setup

---

## 🚀 Quick Start

### Development Setup
```bash
# 1. Start infrastructure (Ollama, LiteLLM, n8n, PostgreSQL)
./gradlew developmentUp

# 2. Run backend (in separate terminal)
./gradlew :chatbot-backend:bootRun

# 3. Run frontend (in separate terminal)
cd chatbot
npm install
npm run dev

# 4. Access application
# Frontend: http://localhost:5173
# Backend: http://localhost:8080
# n8n: http://localhost:5678
```

### Production Deployment
```bash
# Build and start all services
./gradlew up

# Or use Docker Compose directly
docker compose up -d
```

---

## 🏗️ Architecture Quick Reference

### Tech Stack
- **Backend**: Spring Boot 3.4 + WebFlux + R2DBC + PostgreSQL 16
- **Frontend**: React 19 + Vite + TypeScript + Zustand
- **LLM**: OpenAI API (via LiteLLM proxy)
- **Tools**: MCP (Model Context Protocol)
- **Automation**: n8n

### Key Packages

#### Backend
```
app.chatbot/
├── conversation/        # Chat conversations, messages, tool calls
├── mcp/                 # MCP server integration
├── responses/           # OpenAI streaming (SSE)
├── security/            # API key encryption
└── config/              # Spring configuration
```

#### Frontend
```
src/
├── components/          # React UI components
├── store/               # Zustand state management
├── services/            # API client
├── hooks/               # Custom React hooks
├── types/               # TypeScript types
└── utils/               # Utility functions
```

---

## 📊 Data Flow Diagrams

### Chat Message Flow
```
User Input → ChatInput
    ↓
chatStore.sendMessage()
    ↓
POST /api/responses/stream (SSE)
    ↓
ResponseStreamService
    ↓
OpenAI API (with MCP tools)
    ↓
Tool Call? → McpClientService → MCP Server
    ↓
Stream Response → Frontend
    ↓
Update ChatHistory
```

### MCP Tool Execution Flow
```
OpenAI requests tool call
    ↓
Check approval policy
    ├─ ALWAYS_ALLOW → Execute immediately
    ├─ ALWAYS_DENY → Return error
    └─ ASK_USER:
        ├─ Emit approval_required event
        ├─ User approves/denies
        └─ Execute if approved
    ↓
McpClientService.callToolAsync()
    ↓
McpSessionRegistry.getOrCreateSession()
    ↓
McpAsyncClient → MCP Server
    ↓
Return result to OpenAI
    ↓
OpenAI generates final response
```

---

## 🔑 Key Components

### Backend

#### ResponseStreamService (800+ lines) ⭐
- **Purpose**: Orchestrates OpenAI streaming
- **Location**: `chatbot-backend/src/main/java/app/chatbot/responses/ResponseStreamService.java`
- **Key Methods**:
  - `streamResponses()` - Main streaming entry point
  - `executeToolWithApproval()` - Tool execution with approval check
- **Note**: Needs refactoring (too complex)

#### McpSessionRegistry (430+ lines)
- **Purpose**: Manages MCP client lifecycle
- **Location**: `chatbot-backend/src/main/java/app/chatbot/mcp/McpSessionRegistry.java`
- **Key Methods**:
  - `getOrCreateSession()` - Get or create MCP client
  - `closeSession()` - Close specific session
  - `closeAllSessions()` - Graceful shutdown

#### McpServerService
- **Purpose**: CRUD for MCP servers + connection management
- **Location**: `chatbot-backend/src/main/java/app/chatbot/mcp/McpServerService.java`
- **Key Methods**:
  - `verifyConnection()` - Test MCP connection
  - `syncCapabilities()` - Refresh tools/resources cache

### Frontend

#### chatStore (1000+ lines) ⭐
- **Purpose**: Chat state management (Zustand)
- **Location**: `chatbot/src/store/chatStore.ts`
- **Key Actions**:
  - `sendMessage()` - Send message and stream response
  - `approveToolExecution()` - Approve/deny tool execution
  - `loadConversation()` - Load conversation history
- **Note**: Needs refactoring (too complex)

#### ChatHistory Component
- **Purpose**: Displays messages with markdown
- **Location**: `chatbot/src/components/ChatHistory.tsx`

#### UserApprovalDialog Component
- **Purpose**: Tool approval modal
- **Location**: `chatbot/src/components/UserApprovalDialog.tsx`

---

## 🛠️ Common Tasks

### Add New MCP Server (Frontend)
```typescript
// 1. Open Settings → MCP Servers tab
// 2. Click "Add Server"
// 3. Fill form:
//    - Name: "My MCP Server"
//    - Base URL: "http://server:8080/mcp/sse"
//    - Transport: SSE
//    - API Key: (optional)
// 4. Click Save
// 5. Click Verify to test connection
```

### Add New Tool (Backend via MCP)
```
1. Deploy MCP server with tool implementation
2. Add server to database (via frontend or API)
3. Tools automatically discovered and cached
4. Tools available in chat (auto-injected into OpenAI requests)
```

### Debug Streaming Issues
```bash
# 1. Check backend logs
./gradlew :chatbot-backend:bootRun

# 2. Check frontend console (browser DevTools)
# Look for SSE events and errors

# 3. Check OpenAI API logs
curl http://localhost:4000/v1/models

# 4. Check MCP server logs
docker logs <mcp-container>
```

### Add New Approval Policy
```typescript
// Frontend (Settings → MCP Servers → Approval Policies)
// OR via API:
PUT /api/mcp/approval-policies
{
  "serverId": "n8n-server-1",
  "toolName": "dangerous_tool",
  "policy": "ALWAYS_DENY"
}
```

---

## 🐛 Troubleshooting

### Backend won't start
- Check Java version: `java -version` (need 17+)
- Check database connection (PostgreSQL or H2)
- Check port 8080 is available: `lsof -i :8080`

### Frontend won't start
- Check Node version: `node -v` (need 18+)
- Delete `node_modules` and reinstall: `rm -rf node_modules && npm install`
- Check port 5173 is available

### MCP server connection fails
- Check server is running: `curl http://server:8080/health`
- Check firewall rules
- Check API key is correct
- Check logs: `docker logs <mcp-container>`

### Streaming hangs
- Check OpenAI API is accessible: `curl http://localhost:4000/v1/models`
- Check network connection
- Check backend logs for timeout errors
- Try aborting and restarting stream

### Tool execution fails
- Check MCP server is CONNECTED (status badge in UI)
- Check tool exists: View capabilities in MCP panel
- Check approval policy doesn't block tool
- Check tool arguments are valid

---

## 📈 Performance Tips

### Backend
- Use Spring Cache for MCP capabilities
- Enable connection pooling for R2DBC
- Use reactive patterns (avoid `.block()`)
- Set appropriate timeouts

### Frontend
- Use granular Zustand selectors to minimize re-renders
- Lazy load components with `React.lazy()`
- Use virtual scrolling for long message lists
- Debounce user input

### Database
- Add indexes on frequently queried columns
- Use pagination for large result sets
- Monitor query performance with `EXPLAIN ANALYZE`

---

## 🔒 Security Best Practices

### API Keys
- Never commit API keys to repository
- Use environment variables
- Encrypt sensitive data in database (AES-GCM)

### Tool Execution
- Use approval policies for untrusted tools
- Default to `ASK_USER` policy
- Log all tool executions

### CORS
- Configure allowed origins in production
- Don't use `*` in production

---

## 📚 Further Reading

- [Model Context Protocol Spec](https://spec.modelcontextprotocol.io/)
- [Spring WebFlux Documentation](https://docs.spring.io/spring-framework/reference/web/webflux.html)
- [React Documentation](https://react.dev/)
- [Zustand Documentation](https://zustand.docs.pmnd.rs/)
- [OpenAI API Reference](https://platform.openai.com/docs/api-reference)

---

## 🤝 Contributing

### Before Making Changes
1. Read relevant AGENTS.md files
2. Check CODE_QUALITY_REVIEW.md for known issues
3. Run tests: `./gradlew test` (backend), `npm test` (frontend)
4. Follow existing patterns and conventions

### Code Style
- **Backend**: Follow Google Java Style Guide
- **Frontend**: Follow Airbnb React/TypeScript Style Guide
- **Naming**: Use descriptive names (avoid abbreviations)
- **Comments**: Document complex logic, not obvious code

### Pull Request Checklist
- [ ] Tests added/updated
- [ ] Documentation updated
- [ ] No hardcoded values (use configuration)
- [ ] Error handling added
- [ ] Logging added where appropriate
- [ ] Code reviewed by peer

---

## 📞 Getting Help

### Documentation
- Start with [AGENTS.md](./AGENTS.md) for overview
- Check package-specific AGENTS.md for details
- Review [CODE_QUALITY_REVIEW.md](./CODE_QUALITY_REVIEW.md) for known issues

### Debugging
- Enable debug logging: `logging.level.app.chatbot=DEBUG`
- Check browser console for frontend errors
- Use Spring Boot Actuator endpoints: `/actuator/health`, `/actuator/metrics`

### Common Questions
- **Q: How do I add a new tool?**
  - A: Deploy MCP server with tool, add server via UI, tools auto-discovered

- **Q: How do I change the LLM model?**
  - A: Settings panel → Model dropdown

- **Q: Can I use a different database?**
  - A: Yes, R2DBC supports PostgreSQL, MySQL, H2. Update `application.properties`

- **Q: How do I deploy to production?**
  - A: See [DEPLOYMENT_GUIDE.md](./DEPLOYMENT_GUIDE.md)

---

## 🎯 Quick Links

### API Endpoints
- **Backend**: http://localhost:8080
- **Frontend**: http://localhost:5173
- **n8n**: http://localhost:5678
- **LiteLLM**: http://localhost:4000
- **Ollama**: http://localhost:11434

### Health Checks
- Backend: http://localhost:8080/actuator/health
- LiteLLM: http://localhost:4000/health

### Useful Commands
```bash
# Backend
./gradlew :chatbot-backend:bootRun    # Run backend
./gradlew :chatbot-backend:test       # Run tests
./gradlew :chatbot-backend:build      # Build JAR

# Frontend
cd chatbot
npm run dev                            # Run dev server
npm run build                          # Build for production
npm run lint                           # Lint code

# Docker
docker compose up -d                   # Start all services
docker compose down                    # Stop all services
docker compose logs -f <service>       # View logs

# Gradle tasks
./gradlew developmentUp                # Start infrastructure
./gradlew ollamaListModels            # List Ollama models
./gradlew ollamaInstallModels         # Install models
```

---

**Last Updated**: 2025-11-06

For questions or issues, check the documentation or review the code in the relevant package.
