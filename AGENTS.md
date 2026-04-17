# Repository Guidelines

## Project Structure & Module Organization
This repository is a Maven multi-module Spring Boot project. The root `pom.xml` manages versions and aggregates modules such as `aichatpilot-common`, `aichatpilot-user`, `aichatpilot-gateway`, `aichatpilot-chat`, and `aichatpilot-knowledge`. Java code lives under each module’s `src/main/java`, configuration under `src/main/resources`, and tests under `src/test/java`. Infrastructure assets are in `docker/`, startup helpers in `scripts/`, environment templates in `.env.*.example`, and working guides in `docs/` and `docs-bak/`.

## Build, Test, and Development Commands
- `mvn clean install -DskipTests` — build all modules quickly.
- `mvn test` — run the full test suite from the repo root.
- `mvn -pl aichatpilot-user -am spring-boot:run` — start the user service and any required module dependencies.
- `mvn -pl aichatpilot-user -am compile -DskipTests` — compile one module during focused development.
- `.\scripts\run-user.ps1`, `.\scripts\run-gateway.ps1`, `.\scripts\run-knowledge.ps1` — start core services with the selected profile and env file.

Prefer the scripted startup flow plus `.env.local.example`, `.env.dev.example`, and `.env.prod.example`. Keep local-only instructions aligned with `docs/环境配置与启动规范.md` and related run/test guides.

## Coding Style & Naming Conventions
Use Java 17, 4-space indentation, and standard Spring conventions. Keep package names lowercase under `com.yyh.*`; use `PascalCase` for classes, `camelCase` for fields and methods, and suffix DTOs with `Request` / `Response`. Prefer Lombok where the module already uses it, and keep controller/service/mapper layering explicit. Match existing patterns before introducing new abstractions.

When adding a new service module, mirror the current convention used by `user`, `gateway`, and `knowledge`: provide `bootstrap.yml`, `application.yml`, `application-local.yml`, `application-dev.yml`, and `application-prod.yml`, plus matching run scripts where needed.

## Testing Guidelines
Tests belong in the owning module under `src/test/java`. Use Spring Boot Test / JUnit 5 from `spring-boot-starter-test`. Name test classes `*Test` and keep them focused on one service, controller, or utility. When changing request flow or security logic, add or update at least one targeted test for the touched module before broadening to full-project verification.

## Commit & Pull Request Guidelines
Recent commits use short, direct subjects such as `修复鉴权流程` and `update gitignore`. Follow that style: one concise summary line, preferably imperative, and keep unrelated changes out of the same commit. Pull requests should include scope, affected modules, config changes, manual test steps, and screenshots only when UI or API docs behavior changes.

## Security & Configuration Tips
Do not commit real secrets, tokens, or environment-specific passwords. Keep JWT, MySQL, Redis, Nacos, and MinIO settings externalized in YAML plus env overrides. Do not hardcode `localhost`, fixed IPs, ports, bucket names, or absolute Windows paths in Java code. Use environment variables for all deploy-sensitive values and keep defaults suitable for local development only.

Keep modules Docker-ready even when developing locally:
- avoid machine-specific file paths such as `E:\...`
- avoid relying on manual IDE-only startup settings
- keep ports configurable through `${...}` placeholders
- keep service URLs configurable for both direct local access and Nacos-based routing
- document new dependencies, startup order, and required env vars in `docs/`

When testing auth flows, verify both protected endpoints and failure responses, not only login success.
