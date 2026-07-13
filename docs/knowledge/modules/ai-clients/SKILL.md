---
name: ai-clients
description: External AI API clients — WebClient-based image generation, Coze workflow client
---

# AI Clients

External AI API clients using Spring WebClient with shared connection pool.

## Code Map

| Class | Role |
|-------|------|
| `Image2Client` | Interface for image generation |
| `WebClientImage2Client` | Implementation — Reactor Netty WebClient, HTTP/1.1, SSL handling |
| `ImageGenerationRequest` | Request model: prompt, model, size, negative prompt |
| `GeneratedImage` | Response model: URL, status, metadata |
| `CozeClient` | Coze workflow client for scene generation |

## WebClient Configuration

- Pool name: `image2-pool`
- Max 50 connections
- 60s idle timeout
- Force HTTP/1.1 (not HTTP/2)
- JVM DNS resolver (not Netty DNS)
- `@PreDestroy` calls `connectionProvider.dispose()`

## SSL on Windows

Required JVM flag: `-Dio.netty.handler.ssl.noOpenSsl=true`

## DNS Caching

Tuned for container/cloud: `-Dsun.net.inetaddr.negative.ttl=0 -Dnetworkaddress.cache.ttl=10`

## Invariants

- WebClient must be shared (not created per-request). Created once in constructor.
- Connection pool must be explicitly disposed on shutdown via `@PreDestroy`.
- Image requests normally receive the user's enabled provider profile resolved by `config_id`; `artverse.image.*` remains a compatibility fallback when no saved profile exists.
- Error responses (401, 429, 5xx) must be mapped to clear `BusinessException` messages.
