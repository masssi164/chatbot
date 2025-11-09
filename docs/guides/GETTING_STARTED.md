# Getting Started

Welcome to the Chatbot project! This guide will help you set up your development environment and start building.

## Prerequisites

Before you begin, ensure you have the following installed:

- **Java 17+** - [Download](https://adoptium.net/)
- **Node.js 18+** - [Download](https://nodejs.org/)
- **Docker & Docker Compose** - [Download](https://www.docker.com/products/docker-desktop)
- **Git** - [Download](https://git-scm.com/)

### Verify Installation

```bash
# Check Java version
java -version  # Should be 17 or higher

# Check Node version
node -v  # Should be v18 or higher
npm -v

# Check Docker
docker --version
docker compose version

# Check Git
git --version
```

## Quick Start (5 minutes)

### 1. Clone the Repository

```bash
git clone https://github.com/your-org/chatbot.git
cd chatbot
```

### 2. Start Infrastructure Services

This starts PostgreSQL, n8n, LiteLLM, and Ollama:

```bash
./gradlew developmentUp
```

Wait for all services to be healthy. You should see:
```
✓ PostgreSQL is ready
✓ n8n is ready
✓ LiteLLM is ready
✓ Ollama is ready
```

### 3. Run Backend

In a new terminal:

```bash
./gradlew :chatbot-backend:bootRun
```

The backend will start on http://localhost:8080

### 4. Run Frontend

In another new terminal:

```bash
cd chatbot
npm install
npm run dev
```

The frontend will start on http://localhost:5173

### 5. Open the Application

Open your browser and navigate to:
- **Frontend**: http://localhost:5173
- **Backend Health**: http://localhost:8080/actuator/health
- **n8n**: http://localhost:5678 (for workflow management)

## Project Structure

```
chatbot/
├── chatbot/                    # React frontend
│   ├── src/
│   │   ├── components/        # React components
│   │   ├── store/             # Zustand state management
│   │   ├── services/          # API client
│   │   └── types/             # TypeScript types
│   ├── Dockerfile
│   └── package.json
│
├── chatbot-backend/           # Spring Boot backend
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/app/chatbot/
│   │   │   │   ├── conversation/    # Chat logic
│   │   │   │   ├── mcp/             # MCP integration
│   │   │   │   ├── responses/       # OpenAI streaming
│   │   │   │   └── security/        # Encryption
│   │   │   └── resources/
│   │   │       ├── application.properties
│   │   │       └── db/migration/    # Flyway migrations
│   │   └── test/
│   ├── Dockerfile
│   └── build.gradle
│
├── config/                    # Shared configuration
│   └── litellm.config.yaml   # LiteLLM configuration
│
├── docs/                      # Documentation
│   ├── architecture/          # Architecture docs
│   ├── guides/                # User guides
│   └── api/                   # API documentation
│
├── docker-compose.yml         # Production deployment
├── docker-compose.dev.yml     # Development services
├── build.gradle               # Root Gradle build
└── settings.gradle            # Multi-project setup
```

## Development Workflow

### Backend Development

#### Run Tests
```bash
./gradlew :chatbot-backend:test
```

#### Run with Hot Reload
```bash
./gradlew :chatbot-backend:bootRun
```

Changes to Java files will trigger automatic reload with Spring Boot DevTools.

#### Build JAR
```bash
./gradlew :chatbot-backend:build
```

#### Check Code Coverage
```bash
./gradlew :chatbot-backend:jacocoTestReport
open chatbot-backend/build/reports/jacoco/test/html/index.html
```

### Frontend Development

#### Run Dev Server
```bash
cd chatbot
npm run dev
```

Changes to TypeScript/React files will hot reload automatically.

#### Run Linter
```bash
npm run lint
```

#### Build for Production
```bash
npm run build
```

Output will be in `chatbot/dist/`

#### Preview Production Build
```bash
npm run preview
```

## Configuration

### Environment Variables

Create a `.env` file in the root directory:

```bash
# LLM Configuration
OPENAI_BASE_URL=http://localhost:4000
OPENAI_API_KEY=sk-local-master

# Database
DB_HOST=localhost
DB_PORT=5432
DB_NAME=chatbot_db
DB_USER=postgres
DB_PASSWORD=postgres

# n8n
N8N_HOST=localhost
N8N_PORT=5678
N8N_API_KEY=your-n8n-api-key

# Security
MCP_ENCRYPTION_KEY=your-32-character-secret-key-here

# Frontend
VITE_API_BASE_URL=http://localhost:8080
VITE_N8N_BASE_URL=http://localhost:5678
```

### Backend Configuration

Edit `chatbot-backend/src/main/resources/application.properties`:

```properties
# Server
server.port=8080

# Database (development - H2)
spring.r2dbc.url=r2dbc:h2:mem:///chatbotdb
spring.flyway.url=jdbc:h2:mem:chatbotdb

# OpenAI
openai.base-url=${OPENAI_BASE_URL:http://localhost:4000}
openai.api-key=${OPENAI_API_KEY}

# Logging
logging.level.app.chatbot=DEBUG
```

### Frontend Configuration

Edit `chatbot/.env`:

```properties
VITE_API_BASE_URL=http://localhost:8080
VITE_N8N_BASE_URL=http://localhost:5678
```

## First Steps

### 1. Create Your First Conversation

1. Open http://localhost:5173
2. Click "New Chat" button
3. Type a message: "Hello, how are you?"
4. Press Enter or click Send
5. Watch the response stream in real-time!

### 2. Configure an LLM Model

The application uses LiteLLM as a gateway to various LLM providers.

#### Option A: Use Local Ollama Models

```bash
# Install CPU-friendly models (llama3.2:1b recommended)
./gradlew ollamaInstallModels

# Or manually:
docker exec ollama ollama pull llama3.2:1b
```

Models are already configured in `config/litellm.config.yaml`:
- llama3.2:1b (recommended, 1B params)
- phi3.5:3.8b (balanced, 3.8B params)
- qwen2.5:1.5b (fast, 1.5B params)

#### Option B: Use OpenAI API

1. Get an API key from https://platform.openai.com/
2. Update `.env`:
   ```
   OPENAI_BASE_URL=https://api.openai.com/v1
   OPENAI_API_KEY=sk-your-real-api-key
   ```
3. Restart backend

### 3. Add Your First MCP Server

MCP (Model Context Protocol) allows you to add tools and workflows to your chatbot.

#### Configure n8n MCP Server

1. Open http://localhost:5678 (n8n)
2. Create a workflow (e.g., "Get Weather")
3. Activate the workflow
4. In Chatbot UI:
   - Go to Settings → MCP Servers
   - Click "Add Server"
   - Name: "n8n Workflows"
   - Base URL: `http://n8n:5678/api/mcp/sse`
   - Transport: SSE
   - API Key: (your n8n API key)
   - Click Save
   - Click "Verify Connection"

5. Your n8n workflows are now available as tools!

### 4. Test Tool Execution

1. In chat, type: "Execute my workflow"
2. If tool approval is needed, a dialog will appear
3. Click "Approve" to execute
4. Watch the tool execute and see the result!

## Common Development Tasks

### Add a New REST Endpoint

**Backend** (`chatbot-backend/src/main/java/app/chatbot/...`):

```java
@RestController
@RequestMapping("/api/hello")
public class HelloController {
    
    @GetMapping
    public Mono<ResponseEntity<String>> hello() {
        return Mono.just(ResponseEntity.ok("Hello, World!"));
    }
}
```

**Frontend** (`chatbot/src/services/apiClient.ts`):

```typescript
export const getHello = async (): Promise<string> => {
  const response = await fetch('/api/hello');
  return response.text();
};
```

### Add a New React Component

```typescript
// chatbot/src/components/MyComponent.tsx
import React from 'react';

interface MyComponentProps {
  message: string;
}

export const MyComponent: React.FC<MyComponentProps> = ({ message }) => {
  return (
    <div className="my-component">
      <p>{message}</p>
    </div>
  );
};
```

### Add State to Zustand Store

```typescript
// chatbot/src/store/myStore.ts
import { create } from 'zustand';

interface MyState {
  count: number;
  increment: () => void;
  decrement: () => void;
}

export const useMyStore = create<MyState>((set) => ({
  count: 0,
  increment: () => set((state) => ({ count: state.count + 1 })),
  decrement: () => set((state) => ({ count: state.count - 1 })),
}));
```

### Add a Database Migration

```sql
-- chatbot-backend/src/main/resources/db/migration/V8__add_my_table.sql
CREATE TABLE my_table (
  id BIGSERIAL PRIMARY KEY,
  name VARCHAR(255) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

Migrations run automatically on startup.

## Debugging

### Backend Debugging (IntelliJ IDEA)

1. Open `chatbot-backend` in IntelliJ
2. Run → Edit Configurations
3. Add new "Spring Boot" configuration
4. Main class: `app.chatbot.ChatbotBackendApplication`
5. Set breakpoints in your code
6. Click Debug button

### Frontend Debugging (Browser DevTools)

1. Open http://localhost:5173
2. Open Browser DevTools (F12)
3. Go to Sources tab
4. Find your `.tsx` files
5. Set breakpoints
6. Interact with UI to trigger breakpoints

### View Logs

```bash
# Backend logs (if running via Gradle)
./gradlew :chatbot-backend:bootRun

# Docker logs
docker logs chatbot-backend
docker logs n8n
docker logs ollama

# All logs
docker compose logs -f
```

## Testing

### Backend Tests

```bash
# Run all tests
./gradlew :chatbot-backend:test

# Run specific test
./gradlew :chatbot-backend:test --tests ConversationServiceTest

# Run with coverage
./gradlew :chatbot-backend:jacocoTestReport
```

### Frontend Tests

```bash
cd chatbot

# Run tests (if configured)
npm test

# Run linter
npm run lint
```

## Production Deployment

### Docker Compose Deployment

```bash
# Build and start all services
./gradlew up

# Or use Docker Compose directly
docker compose up -d
```

Services will be available at:
- Frontend: http://localhost:3000
- Backend: http://localhost:8080
- n8n: http://localhost:5678

### Stop Services

```bash
./gradlew down

# Or
docker compose down
```

## Troubleshooting

### Port Already in Use

```bash
# Find process using port 8080
lsof -i :8080
# Or
netstat -an | grep 8080

# Kill process
kill -9 <PID>
```

### Database Connection Error

```bash
# Check PostgreSQL is running
docker ps | grep postgres

# View PostgreSQL logs
docker logs postgres

# Connect to database
docker exec -it postgres psql -U postgres -d chatbot_db
```

### Frontend Can't Connect to Backend

1. Check backend is running: `curl http://localhost:8080/actuator/health`
2. Check CORS configuration in backend
3. Verify `VITE_API_BASE_URL` in frontend `.env`
4. Check browser console for errors

### MCP Server Connection Failed

1. Check server URL is correct
2. Verify server is running
3. Check API key is valid
4. View backend logs for detailed error
5. Click "Verify Connection" in UI

## Next Steps

Now that you have the development environment set up, explore:

- **[Architecture Documentation](../architecture/SYSTEM_OVERVIEW.md)** - Understand the system design
- **[API Documentation](../api/REST_API.md)** - Learn about available endpoints
- **[Deployment Guide](./DEPLOYMENT.md)** - Deploy to production
- **[Contributing Guide](../../CONTRIBUTING.md)** - Contribute to the project

## Getting Help

- Check the [documentation](../README.md)
- Search [existing issues](https://github.com/your-org/chatbot/issues)
- Create a [new issue](https://github.com/your-org/chatbot/issues/new)
- Join our community chat (if available)

Happy coding! 🚀
