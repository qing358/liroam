# BRIEF（短基线）

> 产品意图草稿见 `docs/project.md`。选型与边界拍板后，把稳定口径收敛进本文件，并保持短小。

## 文档地图

| 文档 | 用途 |
|------|------|
| `project.md` | 立项草稿 / 功能意向 |
| `upstream.md` | 上游 Lilishop 来源、版本、改动清单、已知上游问题与升级方法 |
| `../README.md` | 目录、本机一键启停、端口、演示数据、密钥变量名、测试命令 |
| `../AGENTS.md` | Agent 强制约束 |
| Cursor Project `preferences.md` | 跨项目开发偏好 |

## 工程基线（P0 已落地）

- 单仓：`shop/` 为 Lilishop v5.0.6（后端 + 前端 subtree，docker 仓摘取文件），`modules/` 为自研 Maven 模块（P1 起）。
- 栈：Java 21 / Spring Boot 4 / MyBatis-Plus；MySQL 8.0.30、Redis 7.0.4（本机专用 compose，端口唯一登记 `deploy/ports.env`，39xxx 段）。
- 验证：`node scripts/dev.mjs health`；测试见 README「测试」，CI 为 `.github/workflows/ci.yml`。

## 工程方法

见 `.cursor/rules/cross-project-dev.mdc` 与知识库《跨项目开工约定》。
