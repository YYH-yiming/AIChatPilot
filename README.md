# AIChatPilot

AIChatPilot 是一个基于 Spring Boot / Spring Cloud 的 Maven 多模块项目，当前已完成前四步基础搭建，`common` 与 `user` 模块可在本机独立运行验证。

## 模块结构

- `aichatpilot-common`：公共返回体、异常处理、JWT 工具
- `aichatpilot-user`：注册、登录、用户信息、Spring Security + JWT
- `aichatpilot-gateway` / `aichatpilot-chat` / `aichatpilot-knowledge` 等：后续扩展模块
- `docker/`：MySQL、Redis、Nacos、MinIO 等基础设施
- `docs/`：本地测试、部署、配置规范文档

## 环境约定

项目已切换为 `profile + 环境变量` 的配置方式：

- `local`：本机开发，默认关闭 Redis 自动配置，Nacos 默认关闭
- `dev`：开发环境，支持连接远程 MySQL / Redis / Nacos
- `prod`：生产环境，默认关闭 Knife4j，日志更保守

默认入口配置：

- `aichatpilot-user/src/main/resources/application.yml`
- `aichatpilot-user/src/main/resources/bootstrap.yml`

环境覆盖文件：

- `aichatpilot-user/src/main/resources/application-local.yml`
- `aichatpilot-user/src/main/resources/application-dev.yml`
- `aichatpilot-user/src/main/resources/application-prod.yml`

## 快速开始

### 1. 初始化数据库

执行：

```bash
mysql -u root -p < docker/mysql/init.sql
```

### 2. 准备环境变量

复制示例文件，自行填入实际值：

- `.env.local.example`
- `.env.dev.example`
- `.env.prod.example`

本地开发通常使用 `local`。

### 3. 构建项目

```bash
mvn clean install -DskipTests
```

### 4. 启动用户模块

```bash
mvn -pl aichatpilot-user -am -DskipTests install
mvn -pl aichatpilot-user spring-boot:run
```

也可以直接使用脚本：

```powershell
.\scripts\run-user.ps1
.\scripts\run-user.ps1 -Profile dev
```

```bash
./scripts/run-user.sh
./scripts/run-user.sh dev
```

### 5. 启动网关模块

```powershell
.\scripts\run-gateway.ps1
.\scripts\run-gateway.ps1 -Profile dev
```

```bash
./scripts/run-gateway.sh
./scripts/run-gateway.sh dev
```

## 推荐文档

- `docs/环境配置与启动规范.md`
- `docs/本地测试指南_不依赖Docker.md`
- `docs/服务器Docker部署指南.md`

## 当前建议

开发阶段优先在本机推进功能；部署到服务器时，只修改环境变量或服务器上的 env 文件，不直接改仓库中的 yml。
