# Documentation Index

Welcome to the Chatbot documentation! This index helps you find what you need quickly.

## 📚 Documentation Structure

```
docs/
├── architecture/           # System architecture and design
├── guides/                 # User and developer guides
└── api/                    # API reference documentation
```

## 🚀 Getting Started

**New to the project?** Start here:

1. **[Getting Started Guide](guides/GETTING_STARTED.md)** - Setup development environment
2. **[System Architecture Overview](architecture/SYSTEM_OVERVIEW.md)** - Understand the system
3. **[REST API Reference](api/REST_API.md)** - Explore the API

## 📖 Documentation by Role

### For New Developers

**First Steps:**
1. [Getting Started](guides/GETTING_STARTED.md) - Environment setup
2. [System Overview](architecture/SYSTEM_OVERVIEW.md) - Architecture basics
3. [Frontend-Backend Communication](architecture/FRONTEND_BACKEND_COMMUNICATION.md) - How components interact

**Development:**
4. [REST API Reference](api/REST_API.md) - API endpoints
5. [OpenAI Responses API](architecture/OPENAI_RESPONSES_API.md) - Streaming implementation

### For DevOps/SRE

**Deployment:**
1. [Deployment Guide](guides/DEPLOYMENT.md) - Production deployment
2. [System Overview](architecture/SYSTEM_OVERVIEW.md) - Infrastructure architecture

**Operations:**
3. [Monitoring Guide](guides/MONITORING.md) - Observability and metrics (coming soon)
4. [Troubleshooting](guides/TROUBLESHOOTING.md) - Common issues (coming soon)

### For Architects

**Architecture:**
1. [System Overview](architecture/SYSTEM_OVERVIEW.md) - High-level design
2. [MCP Integration](architecture/MCP_INTEGRATION.md) - Tool execution architecture
3. [OpenAI Responses API](architecture/OPENAI_RESPONSES_API.md) - Streaming architecture
4. [Frontend-Backend Communication](architecture/FRONTEND_BACKEND_COMMUNICATION.md) - API patterns

### For API Consumers

**API Reference:**
1. [REST API Reference](api/REST_API.md) - Complete API docs
2. [SSE Events Reference](api/SSE_EVENTS.md) - Streaming events (coming soon)

## 📁 Documentation Categories

### Architecture

Understand the system design and technical decisions.

- **[System Overview](architecture/SYSTEM_OVERVIEW.md)**
  - Component diagram
  - Technology stack
  - Service dependencies
  - Database schema
  - Security architecture
  - Deployment architecture

- **[OpenAI Responses API Integration](architecture/OPENAI_RESPONSES_API.md)**
  - Streaming architecture
  - SSE event types
  - Tool approval flow
  - Error handling

- **[Frontend-Backend Communication](architecture/FRONTEND_BACKEND_COMMUNICATION.md)**
  - REST API patterns
  - SSE implementation
  - State management
  - Error handling

- **[MCP Integration](architecture/MCP_INTEGRATION.md)**
  - MCP architecture
  - Session lifecycle
  - Tool discovery and caching
  - Tool execution flow
  - Approval system

### Guides

Step-by-step instructions for common tasks.

- **[Getting Started](guides/GETTING_STARTED.md)**
  - Prerequisites
  - Quick start (5 minutes)
  - Project structure
  - Development workflow
  - First steps
  - Troubleshooting

- **[Deployment Guide](guides/DEPLOYMENT.md)**
  - Docker Compose deployment
  - Kubernetes deployment
  - Cloud platforms (AWS, GCP, Azure)
  - Performance tuning
  - Security checklist
  - Maintenance

### API Reference

Complete API documentation for developers.

- **[REST API Reference](api/REST_API.md)**
  - Conversation management
  - Streaming responses
  - MCP server management
  - Tool approval policies
  - Health checks
  - Error responses

## 🔍 Quick Links

### Common Tasks

- **Set up development environment**: [Getting Started](guides/GETTING_STARTED.md#quick-start-5-minutes)
- **Add a new MCP server**: [Getting Started](guides/GETTING_STARTED.md#3-add-your-first-mcp-server)
- **Deploy to production**: [Deployment Guide](guides/DEPLOYMENT.md#docker-compose-deployment)
- **Configure LLM models**: [Getting Started](guides/GETTING_STARTED.md#2-configure-an-llm-model)
- **Test tool execution**: [Getting Started](guides/GETTING_STARTED.md#4-test-tool-execution)

### API Endpoints

- **List conversations**: [GET /api/conversations](api/REST_API.md#list-conversations)
- **Stream chat response**: [POST /api/responses/stream](api/REST_API.md#stream-chat-response)
- **List MCP servers**: [GET /api/mcp/servers](api/REST_API.md#list-mcp-servers)
- **Get server capabilities**: [GET /api/mcp/servers/{serverId}/capabilities](api/REST_API.md#get-server-capabilities)

### Architecture Diagrams

- **System components**: [System Overview](architecture/SYSTEM_OVERVIEW.md#component-diagram)
- **Streaming flow**: [OpenAI Responses API](architecture/OPENAI_RESPONSES_API.md#streaming-architecture)
- **Tool execution**: [MCP Integration](architecture/MCP_INTEGRATION.md#tool-execution-flow)
- **Database schema**: [System Overview](architecture/SYSTEM_OVERVIEW.md#database-schema)

## 🎓 Learning Path

### Beginner

**Goal**: Set up and run the application

1. Read [Getting Started](guides/GETTING_STARTED.md)
2. Follow Quick Start steps
3. Explore the UI
4. Try sending messages

**Time**: 30 minutes

### Intermediate

**Goal**: Understand architecture and make changes

1. Read [System Overview](architecture/SYSTEM_OVERVIEW.md)
2. Read [Frontend-Backend Communication](architecture/FRONTEND_BACKEND_COMMUNICATION.md)
3. Review [REST API Reference](api/REST_API.md)
4. Make a simple code change
5. Add a new endpoint

**Time**: 2-3 hours

### Advanced

**Goal**: Master the system and contribute features

1. Read all architecture docs
2. Study [MCP Integration](architecture/MCP_INTEGRATION.md)
3. Study [OpenAI Responses API](architecture/OPENAI_RESPONSES_API.md)
4. Implement a new feature
5. Deploy to production

**Time**: 1 week

## 🔧 Tools and Technologies

### Backend
- Spring Boot 3.4 + WebFlux (reactive)
- Java 17
- R2DBC (reactive database)
- PostgreSQL 16
- Gradle

### Frontend
- React 19
- TypeScript 5.9
- Vite 7.1
- Zustand (state management)

### Infrastructure
- Docker + Docker Compose
- LocalAGI (OpenAI Responses gateway)
- LocalAI (deterministic local runtime)
- n8n (workflow automation)

### Integrations
- OpenAI API
- Model Context Protocol (MCP)
- Server-Sent Events (SSE)

## 📝 Additional Resources

### Internal Documentation
- **[Root README](../README.md)** - Project overview
- **[AGENTS.md](../AGENTS.md)** - Architecture & developer guide
- **[Backend AGENTS.md](../chatbot-backend/AGENTS.md)** - Backend architecture
- **[Frontend AGENTS.md](../chatbot/AGENTS.md)** - Frontend architecture

### External Resources
- [Spring WebFlux Docs](https://docs.spring.io/spring-framework/reference/web/webflux.html)
- [React Documentation](https://react.dev/)
- [Model Context Protocol Spec](https://spec.modelcontextprotocol.io/)
- [OpenAI API Reference](https://platform.openai.com/docs/api-reference)

## 🤝 Contributing

Want to improve the documentation?

1. Check [existing issues](https://github.com/your-org/chatbot/issues)
2. Create a pull request
3. Follow the documentation style guide

### Documentation Style Guide

- Use clear, concise language
- Include code examples
- Add PlantUML diagrams for flows
- Keep sections focused
- Link to related docs
- Update index when adding new docs

## 📞 Getting Help

- **Questions**: Check documentation first
- **Issues**: [GitHub Issues](https://github.com/your-org/chatbot/issues)
- **Discussions**: [GitHub Discussions](https://github.com/your-org/chatbot/discussions)

---

**Last Updated**: 2025-11-07

[Back to Project Root](../README.md)
