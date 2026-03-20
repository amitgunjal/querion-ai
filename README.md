# Querion

Schema-aware NL2SQL assistant for PostgreSQL with streaming responses, query reuse, feedback-driven scoring, and runtime vocabulary generation.

## Overview

Querion is a Spring Boot application that turns natural-language questions into safe PostgreSQL `SELECT` queries, executes them, and returns user-friendly answers in a lightweight chat UI.

It combines:

- schema-aware SQL generation
- conversational fallback for non-data questions
- SSE-based live progress and answer streaming
- query reuse to avoid repeated LLM work
- feedback-aware scoring to avoid trusting low-quality history
- runtime vocabulary generation from the connected database schema

## Screenshots

### Main Chat Experience

![Main chat screen](src/main/resources/screens/Screenshot%202026-03-14%20153147.png)

### Streaming And Response Flow

![Streaming response screen](src/main/resources/screens/Screenshot%202026-03-21%20000521.png)

### History And Insights View

![History and insights screen](src/main/resources/screens/Screenshot%202026-03-21%20000552.png)

## Why It Stands Out

- Safe SQL generation with named parameters and `SELECT`-only rules
- Reuse-aware pipeline that can skip SQL regeneration for repeated query shapes
- Live UX through Server-Sent Events
- Prompts and vocabulary live outside Java code for easier iteration
- Startup-time schema mining to generate temporary runtime vocab files
- Feedback loop that reduces reuse confidence when users rate an answer negatively

## Features

- Natural language to SQL for PostgreSQL
- `CHAT` vs `DATA` classification
- SQL generation with Ollama
- final answer generation from SQL result sets
- key insights generation for insight-style queries
- SSE-based live progress and token streaming
- query history, scores, and user feedback
- exact, template, and lightweight semantic query reuse
- runtime vocabulary generation from DB schema and filter-like columns
- Markdown-capable frontend rendering

## Architecture

### Request Flow

1. The user sends a question from the UI.
2. The backend classifies it as either `CHAT` or `DATA`.
3. For `CHAT` queries, Querion returns a conversational reply and stores history.
4. For `DATA` queries, Querion checks prior successful history for reusable candidates.
5. If a normalized or template match is found, it avoids regenerating SQL.
6. Otherwise, Querion reads schema context, builds a prompt, asks Ollama for SQL JSON, executes the SQL, and asks Ollama for the final answer.
7. The result, metrics, score, and feedback state are saved in history and returned to the UI.

### Query Reuse

Querion currently supports three reuse paths before full SQL generation:

1. Normalized exact match
   Same question after normalization.

2. Template reuse
   Same query shape, different parameter values.
   Example:
   - `get customer with name amit`
   - `get customer with name rahul`

3. Lightweight semantic reuse
   Token-based similarity for close paraphrases.

Template reuse is the main optimization for recurring operational queries where only filter values change.

### Streaming

Querion exposes:

- `GET /api/v1/ask/stream?q=...`

The frontend listens for:

- connection events
- status/progress updates
- metric events
- streamed answer chunks
- completion or failure events

### Runtime Vocabulary Generation

At startup, Querion reads the connected PostgreSQL schema and generates temporary runtime resources under `.runtime-resources`.

Generated artifacts include:

- schema snapshot text
- classifier data nouns
- classifier data seed samples
- filter-value tokens from low-cardinality/status-like columns
- runtime copies of seed files used by classifiers

These generated files are then preferred over bundled classpath resources, which keeps the app adaptable to the connected schema without hardcoding DB vocabulary in Java.

## Prompt And Vocabulary Files

### Prompt Templates

- `src/main/resources/prompts/data-query-prompt.txt`
- `src/main/resources/prompts/insights-query-prompt.txt`
- `src/main/resources/prompts/answer-prompt.txt`
- `src/main/resources/prompts/insights-answer-prompt.txt`

### Vocabulary Resources

- `src/main/resources/classifier/chat-phrases.txt`
- `src/main/resources/classifier/chat-seed-samples.txt`
- `src/main/resources/classifier/data-verbs.txt`
- `src/main/resources/classifier/data-nouns.txt`
- `src/main/resources/classifier/data-seed-samples.txt`
- `src/main/resources/classifier/filter-words.txt`
- `src/main/resources/query-reuse/stop-words.txt`
- `src/main/resources/query-reuse/filter-value-tokens.txt`
- `src/main/resources/query-reuse/synonyms.properties`

### Runtime-Generated Resources

- `.runtime-resources/schema/schema.txt`
- `.runtime-resources/classifier/data-nouns.txt`
- `.runtime-resources/classifier/data-seed-samples.txt`
- `.runtime-resources/classifier/chat-seed-samples.txt`
- `.runtime-resources/query-reuse/filter-value-tokens.txt`

## Tech Stack

- Java 21
- Spring Boot
- Spring Web
- Spring Data JPA
- PostgreSQL
- Ollama
- React via CDN
- Plain CSS frontend

## API Endpoints

### `GET /api/v1/hello`

Simple health-style endpoint.

### `GET /api/v1/ask?q=...`

Returns a final non-streaming response:

```json
{
  "ans": "Final answer",
  "status": "OK",
  "requestId": "..."
}
```

### `GET /api/v1/ask/stream?q=...`

Streams progress, metrics, and answer chunks over SSE.

### `GET /api/v1/history`

Returns recent query history and summary data for the UI.

### `POST /api/v1/feedback?requestId=...&correct=true|false`

Stores one feedback submission per request and updates the stored score.

## Setup

### Prerequisites

- Java 21
- PostgreSQL running locally
- Ollama running locally
- a local Ollama model available, currently configured as `llama3`

### Database Setup

Hibernate DDL is disabled:

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: none
```

Apply the SQL in `db-changes.txt` before starting the app.

Default datasource config:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/orion
    username: postgres
    password: 1234
```

Update `src/main/resources/application.yaml` for your environment.

### Ollama Setup

```powershell
ollama pull llama3
ollama serve
```

Default Ollama config:

```yaml
ollama:
  api_url: http://localhost:11434/api/generate
  model: llama3
  stream: true
```

## Run

Windows:

```powershell
.\mvnw.cmd spring-boot:run
```

Unix-like systems:

```bash
./mvnw spring-boot:run
```

App URL:

```text
http://localhost:8080
```

## Testing

Run tests with:

```powershell
.\mvnw.cmd test
```

Current automated coverage is strongest around query reuse logic.

## Important Files

- `src/main/java/com/nanth/querion/controllers/AiController.java`
- `src/main/java/com/nanth/querion/services/QueryService.java`
- `src/main/java/com/nanth/querion/engine/LlammaEngine.java`
- `src/main/java/com/nanth/querion/services/QueryReuseService.java`
- `src/main/java/com/nanth/querion/services/QueryHistoryService.java`
- `src/main/java/com/nanth/querion/services/SchemaService.java`
- `src/main/java/com/nanth/querion/services/RuntimeVocabularyGenerator.java`
- `src/main/java/com/nanth/querion/services/ApiService.java`
- `src/main/resources/static/app.js`

## Current Notes

- Only safe `SELECT` SQL is allowed.
- Query history stores prompts, SQL, metrics, answers, score, and feedback.
- Negative feedback lowers reuse confidence.
- Prompt rules and vocabulary live outside Java code.
- Runtime vocabulary is generated from the connected schema at startup.
- Template reuse reruns SQL with fresh params instead of reusing stale answers.

## Known Limitations

- Full-schema prompting can still be slow for larger databases.
- Semantic reuse is heuristic, not embedding-based.
- Template reuse works best when new questions roughly follow a known shape.
- Runtime vocabulary generation currently focuses on table names and filter-like columns.
- Frontend libraries are loaded via CDN.
- DB schema setup is still manual.

## Good Next Improvements

- Cache schema text to reduce repeated metadata reads
- Generate richer runtime synonyms from schema naming patterns
- Add integration tests for full streaming flows
- Improve template reuse for more varied wording
- Add Flyway or Liquibase for migrations
- Add Docker support for app + Postgres + Ollama local setup

## License

This repository currently includes the GNU GPL v3 license in [LICENSE](/c:/Users/amitg/OneDrive/Desktop/projects/querion/LICENSE).
