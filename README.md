# 🌉 Gesher Relay

> Gesher (גשר) means "bridge" in Hebrew — a relay that bridges two API worlds.

> Connect **Claude Code** to any OpenAI-compatible model running on **Tanzu Platform** (or anywhere else).

A Spring Boot proxy that translates between the **Anthropic Messages API** and the **OpenAI Chat Completions API** in real-time, including streaming and tool use.

![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0-6DB33F?logo=springboot&logoColor=white)
![Java](https://img.shields.io/badge/Java-25-ED8B00?logo=openjdk&logoColor=white)
![License](https://img.shields.io/badge/License-Apache%202.0-blue)

> ⚠️ **Community Project** — This is not an official product of, nor affiliated with or endorsed by, Anthropic, OpenAI, or Broadcom. All trademarks belong to their respective owners.

---

## ✨ Features

- 🔄 **Full API translation** — Messages, system prompts, tool definitions, tool results
- 📡 **Streaming support** — Real-time SSE translation between Anthropic and OpenAI formats
- 🛠️ **Tool use** — All 25 Claude Code tools (Bash, Read, Edit, Write, Grep, Glob, etc.)
- 🌐 **Web dashboard** — Lists bound AI Services with one-click copy for Claude Code setup
- 🔐 **SSO integration** — Tanzu Platform `p-identity` service binding for authentication
- 📦 **Multi-model support** — Discovers models from single-model and multi-model GenAI bindings
- 🗄️ **Redis-backed** — Sessions and settings shared across scaled instances
- ☁️ **CF-ready** — Deploy to Tanzu Platform with `cf push`

## 📐 Architecture

```
Claude Code  ──(Anthropic API)──▶  Gesher Relay  ──(OpenAI API)──▶  AI Services Model
Claude Code  ◀──(Anthropic API)──  Gesher Relay  ◀──(OpenAI API)──  AI Services Model
```

## 🚀 Quick Start (Local)

### Prerequisites

- Java 25+
- Maven 3.9+
- An OpenAI-compatible model endpoint

### Run

```bash
# Terminal 1 — start the bridge
export TARGET_API_BASE="https://your-openai-endpoint/openai"
export TARGET_API_KEY="your-api-key"
export TARGET_MODEL="your-model-name"
mvn spring-boot:run

# Terminal 2 — start Claude Code
export ANTHROPIC_BASE_URL=http://localhost:8082
export ANTHROPIC_API_KEY=anything
export ANTHROPIC_MODEL=my-model
claude
```

### Environment Variables (Local)

#### Bridge

| Variable | Default | Description |
|----------|---------|-------------|
| `TARGET_API_BASE` | — | Base URL of the OpenAI-compatible endpoint |
| `TARGET_API_KEY` | — | API key for the target endpoint |
| `TARGET_MODEL` | — | Model name to use on the target endpoint |
| `PORT` | `8082` | Port the bridge listens on |

#### Claude Code

| Variable | Value | Description |
|----------|-------|-------------|
| `ANTHROPIC_BASE_URL` | `http://localhost:8082` | Points Claude Code at the bridge |
| `ANTHROPIC_API_KEY` | Any value | Auth token (not validated locally) |
| `ANTHROPIC_MODEL` | e.g. `my-model` | Model name shown in Claude Code |

## ☁️ Deploy to Tanzu Platform

### 1. Build

```bash
mvn clean package -DskipTests
```

### 2. Configure services

Edit `manifest.yml` to list your service bindings:

```yaml
applications:
  - name: gesher-relay
    memory: 768M
    instances: 1
    path: target/gesher-relay-0.1.0-SNAPSHOT.jar
    buildpacks:
      - java_buildpack_offline
    services:
      - sso              # p-identity SSO instance
      - my-genai-service # AI Services binding(s)
      - redis            # Redis for sessions + settings
    env:
      JBP_CONFIG_OPEN_JDK_JRE: '{ jre: { version: 25.+ } }'
```

### 3. Push

```bash
cf push
```

### 4. Use

1. Visit `https://gesher-relay.<apps-domain>` — sign in via SSO
2. The dashboard shows all bound AI models with capabilities
3. Click **Copy** on any model
4. Paste into your terminal — Claude Code connects through the bridge

## 🧠 Context Window Requirements

Claude Code sends a large system prompt (~7K tokens) plus 25 tool definitions (~20K tokens). The model must have enough context to fit this overhead plus your conversation.

| Context | Suitability |
|---------|-------------|
| **32K tokens** | ⚠️ Minimum — short conversations only |
| **64K tokens** | ✅ Recommended — comfortable for coding sessions |
| **128K+ tokens** | 🚀 Ideal — extended sessions without hitting limits |

If a model returns 500 errors, ask your platform operator to increase the max context window in **Tanzu Ops Manager**.

### Managing context manually

Claude Code doesn't know the context window of custom models, so it won't automatically compact the conversation when approaching the limit. Use these commands:

| Command | When to use |
|---------|-------------|
| `/compact` | Summarizes the conversation to free up context. Use when responses slow down or errors appear. Optionally: `/compact focus on the API changes` |
| `/clear` | Resets the conversation. Use when switching tasks. |
| `/context` | Shows what's using space in your context window. |


## 🔐 SSO

When deployed to Tanzu Platform with a `p-identity` service binding:

- The web dashboard requires SSO login
- The `/v1/messages` API uses the GenAI API key for auth (no SSO)
- Sessions are stored in Redis for multi-instance support

When running locally without SSO, the dashboard is open (no auth required).

## 📊 Scaling

The bridge is stateless for API requests and uses Redis for shared state:

- **Sessions** — Stored in Redis via Spring Session
- **API requests** — Stateless, load-balanced across all instances
- **Model settings** — Stored in Redis, shared across instances

Scale freely with `cf scale gesher-relay -i 3`.

## 🏗️ Tech Stack

- **Spring Boot 4.0** with WebFlux (reactive)
- **Spring AI 2.0** for AI Services integration
- **Spring Security** with OAuth2 (SSO)
- **Spring Session** with Redis
- **Reactor Netty** for async HTTP
- **Jackson 3** for JSON processing
- **Java 25**

## 📝 What Gets Translated

| Anthropic | OpenAI | Notes |
|-----------|--------|-------|
| `system` (top-level) | `system` message | Extracted from array of text blocks |
| `messages` (user/assistant) | `messages` (system/user/assistant/tool) | Role mapping + content block conversion |
| `tools` (input_schema) | `tools` (function/parameters) | Schema format translation |
| `tool_use` content blocks | `tool_calls` array | Inline blocks → separate array |
| `tool_result` in user msg | `tool` role messages | Content block → separate message |
| `stream` SSE events | `stream` SSE chunks | Full lifecycle translation |
| `stop_reason` | `finish_reason` | end_turn↔stop, tool_use↔tool_calls |

## 🤝 Contributing

This is a community project. Issues and pull requests are welcome!

## 📄 License

Apache License 2.0

## 🏷️ Releases

Releases are created automatically when a version tag is pushed:

```bash
git tag v0.1.0
git push origin v0.1.0
```

This triggers a GitHub Action that builds the JAR and creates a release with auto-generated release notes.
