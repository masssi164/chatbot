# 🦙 Ollama + LiteLLM Setup Guide

## 🎯 **Quick Start - All-in-One AI Stack**

```bash
# 1. Start everything (includes Ollama + LiteLLM)
./gradlew composeUp

# 2. Install common AI models (after services are up)
./gradlew ollamaInstallModels

# 3. Verify setup
./gradlew ollamaStatus
```

**✅ Ready!** Your chatbot now uses local AI models via OpenAI-compatible API.

---

## 🏗️ **Architecture Overview**

```
Frontend (3000) → Backend (8080) → LiteLLM (4000) → Ollama (11434)
                                      ↓
                                 [OpenAI API]
                                 [Local Models]
```

### **Service Flow:**
1. **Chatbot Backend** sends OpenAI-compatible requests to **LiteLLM**
2. **LiteLLM** routes requests to **Ollama** or external **OpenAI**
3. **Ollama** runs local models (llama3.2, mistral, qwen2.5)
4. Responses flow back through the same chain

---

## 🔧 **Configuration**

### **Environment Variables (.env):**
```bash
# API Gateway (points to LiteLLM)
OPENAI_BASE_URL=http://localhost:4000
OPENAI_API_KEY=sk-local-master

# LiteLLM Settings
LITELLM_MASTER_KEY=sk-local-master
LITELLM_UI_USERNAME=admin
LITELLM_UI_PASSWORD=admin

# Service Ports
OLLAMA_PORT=11434
LITELLM_PORT=4000
```

### **Available Models (config/litellm.config.yaml):**
- `llama3.2` - Latest Llama model (recommended)
- `llama3.1` - Previous Llama version
- `mistral` - Mistral AI model
- `qwen2.5` - Qwen 2.5 model

---

## 🚀 **Deployment Modes**

### **Development Mode (H2 + Local AI):**
```bash
# Start with H2 database + Ollama/LiteLLM
./gradlew developmentUp

# Install models
./gradlew ollamaInstallModels
```

### **Production Mode (PostgreSQL + Local/Remote AI):**
```bash
# Start full stack
./gradlew composeUp

# For production, you can switch to OpenAI:
# OPENAI_BASE_URL=https://api.openai.com/v1
# OPENAI_API_KEY=sk-your-real-openai-key
```

### **Local Development (IDE + External Services):**
```bash
# Start only external services
./gradlew externalServicesUp

# Run backend in IDE with profile:
# -Dspring.profiles.active=dev
# Backend will connect to LiteLLM at localhost:4000
```

---

## 🎮 **Management Commands**

### **Model Management:**
```bash
# Install popular models
./gradlew ollamaInstallModels

# List installed models
./gradlew ollamaListModels

# Check AI stack status
./gradlew ollamaStatus

# Manual model installation
docker compose exec ollama ollama pull llama3.2
docker compose exec ollama ollama pull codellama
```

### **Service Management:**
```bash
# View all services
./gradlew healthCheck

# View logs
./gradlew logs

# Restart everything
./gradlew restart
```

---

## 🌐 **Access Points**

| Service | URL | Purpose |
|---------|-----|---------|
| **Chatbot Frontend** | http://localhost:3000 | Main UI |
| **Chatbot Backend** | http://localhost:8080 | REST API |
| **LiteLLM Gateway** | http://localhost:4000 | AI Gateway |
| **LiteLLM Admin UI** | http://localhost:4000 | Model management |
| **Ollama API** | http://localhost:11434 | Direct model access |
| **n8n Workflows** | http://localhost:5678 | Automation |

---

## 🧪 **Testing the AI Setup**

### **Test LiteLLM Gateway:**
```bash
curl -X POST http://localhost:4000/chat/completions \
  -H "Authorization: Bearer sk-local-master" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "llama3.2",
    "messages": [
      {"role": "user", "content": "Hello! Test message."}
    ]
  }'
```

### **Test Backend Integration:**
```bash
# Through your chatbot backend
curl -X POST http://localhost:8080/api/responses/stream \
  -H "Content-Type: application/json" \
  -d '{
    "conversationId": 1,
    "title": "Test",
    "payload": {
      "model": "llama3.2",
      "messages": [
        {"role": "user", "content": "Hello AI!"}
      ]
    }
  }'
```

---

## 🎯 **Model Selection Strategy**

### **Local Models (Ollama):**
- ✅ **Privacy** - Data stays local
- ✅ **No API costs** - Free usage
- ✅ **Fast response** - No network latency
- ❌ **Resource intensive** - Needs CPU/GPU
- ❌ **Limited capability** - Smaller models

### **Remote Models (OpenAI):**
- ✅ **Powerful** - GPT-4o, latest models
- ✅ **Reliable** - High uptime
- ✅ **Scalable** - No local resources
- ❌ **API costs** - Pay per token
- ❌ **Privacy** - Data sent to OpenAI

### **Hybrid Approach (Recommended):**
```yaml
# Use local for development/testing
OPENAI_BASE_URL=http://localhost:4000  # LiteLLM
OPENAI_API_KEY=sk-local-master

# Switch to OpenAI for production
OPENAI_BASE_URL=https://api.openai.com/v1
OPENAI_API_KEY=sk-your-real-openai-key
```

---

## 🔧 **Advanced Configuration**

### **GPU Support (NVIDIA):**
```yaml
# Uncomment in docker-compose.yml
ollama:
  deploy:
    resources:
      reservations:
        devices:
          - driver: nvidia
            count: 1
            capabilities: [gpu]
```

### **Custom Models:**
```bash
# Add custom model to Ollama
docker compose exec ollama ollama pull custom-model:latest

# Update config/litellm.config.yaml
- model_name: custom-model
  litellm_params:
    model: ollama_chat/custom-model
    api_base: http://ollama:11434
```

### **Load Balancing:**
```yaml
# Multiple model instances in litellm.config.yaml
- model_name: llama3.2
  litellm_params:
    model: ollama_chat/llama3.2
    api_base: http://ollama:11434
    
- model_name: llama3.2  # Same name = load balancing
  litellm_params:
    model: openai/gpt-4o-mini  # Fallback to OpenAI
    api_key: os.environ/OPENAI_API_KEY
```

---

## 🚨 **Troubleshooting**

### **Common Issues:**

1. **"Ollama not responding"**
   ```bash
   # Check Ollama health
   curl http://localhost:11434/api/tags
   
   # Check Docker logs
   docker compose logs ollama
   ```

2. **"LiteLLM connection failed"**
   ```bash
   # Verify LiteLLM is up
   curl http://localhost:4000/health
   
   # Check config
   docker compose logs litellm
   ```

3. **"Models not found"**
   ```bash
   # Install models
   ./gradlew ollamaInstallModels
   
   # List available models
   ./gradlew ollamaListModels
   ```

4. **"Backend can't reach LiteLLM"**
   ```bash
   # Check network connectivity
   docker compose exec chatbot-backend curl http://litellm:4000/health
   ```

### **Resource Requirements:**

| Model | RAM | Storage | CPU/GPU |
|-------|-----|---------|---------|
| llama3.2 (3B) | 4GB | 2GB | CPU sufficient |
| llama3.1 (8B) | 8GB | 5GB | GPU recommended |
| mistral (7B) | 8GB | 4GB | GPU recommended |
| qwen2.5 (7B) | 8GB | 5GB | GPU recommended |

---

## 🎉 **Ready to Chat!**

Your AI-powered chatbot is now ready with:
- ✅ **Local AI models** via Ollama
- ✅ **OpenAI compatibility** via LiteLLM
- ✅ **Flexible deployment** (dev/prod modes)
- ✅ **Easy model management** 
- ✅ **Integrated UI** and workflows

**Start chatting at: http://localhost:3000** 🚀