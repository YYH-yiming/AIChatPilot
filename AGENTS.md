# Repository Guidelines

## Project Structure & Module Organization
This repository is a Maven multi-module Spring Boot project. The root `pom.xml` manages versions and aggregates modules such as `aichatpilot-common`, `aichatpilot-user`, `aichatpilot-gateway`, `aichatpilot-chat`, and `aichatpilot-knowledge`. Java code lives under each module’s `src/main/java`, configuration under `src/main/resources`, and tests under `src/test/java`. Infrastructure and bootstrap assets are in `docker/`, while working notes and step-by-step guides are in `docs/` and `docs-bak/`.

## Build, Test, and Development Commands
- `mvn clean install -DskipTests` — build all modules quickly.
- `mvn test` — run the full test suite from the repo root.
- `mvn -pl aichatpilot-user -am spring-boot:run` — start the user service and any required module dependencies.
- `mvn -pl aichatpilot-user -am compile -DskipTests` — compile one module during focused development.

For local-only testing, disable Nacos and Redis in `aichatpilot-user/src/main/resources/bootstrap.yml` and `aichatpilot-user/src/main/resources/application.yml` as described in `docs/本地测试指南_不依赖Docker.md`.

## Coding Style & Naming Conventions
Use Java 17, 4-space indentation, and standard Spring conventions. Keep package names lowercase under `com.yyh.*`; use `PascalCase` for classes, `camelCase` for fields and methods, and suffix DTOs with `Request` / `Response`. Prefer Lombok where the module already uses it, and keep controller/service/mapper layering explicit. Match existing patterns before introducing new abstractions.

## Testing Guidelines
Tests belong in the owning module under `src/test/java`. Use Spring Boot Test / JUnit 5 from `spring-boot-starter-test`. Name test classes `*Test` and keep them focused on one service, controller, or utility. When changing request flow or security logic, add or update at least one targeted test for the touched module before broadening to full-project verification.

## Commit & Pull Request Guidelines
Recent commits use short, direct subjects such as `修复鉴权流程` and `update gitignore`. Follow that style: one concise summary line, preferably imperative, and keep unrelated changes out of the same commit. Pull requests should include scope, affected modules, config changes, manual test steps, and screenshots only when UI or API docs behavior changes.

## Security & Configuration Tips
Do not commit real secrets, tokens, or environment-specific passwords. Keep JWT, MySQL, Redis, and Nacos settings externalized in YAML or local environment overrides. When testing auth flows, verify both protected endpoints and failure responses, not only login success.
