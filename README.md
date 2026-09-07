# Java AI 智能知识库助手

基于 Spring Boot 3.5 + Spring AI 1.0.9 构建的智能对话应用，融合 **RAG（检索增强生成）**、**Function Calling（工具调用）** 与 **跨会话持久化对话记忆**，支持上传私有文档构建个人知识库。

## 功能特性

### 💬 智能对话
- 接入 DeepSeek（`deepseek-chat`）大模型，OpenAI 兼容协议
- 支持 SSE 流式输出与同步调用两种模式（`/chat`、`/chat/stream`）
- 会话隔离：前端通过 `conversationId` 区分不同会话，存储于 sessionStorage，刷新页面不丢失

### 🧠 对话记忆持久化
- 自定义 `RedisChatMemoryRepository`，将对话历史持久化到 Redis（TTL 7 天，活跃会话自动续期）
- 滑动窗口策略：`MessageWindowChatMemory` 每次注入最近 20 条消息（约 10 轮对话）
- 采用 `PromptChatMemoryAdvisor` 将历史注入 system 消息，确保历史在 RAG 流程中不被丢弃
- 只持久化 USER / ASSISTANT / SYSTEM 消息，工具调用中间消息不跨会话保留
- **应用重启后会话可恢复**，模型依然记得之前的对话内容

### 📚 RAG 知识库检索
- 两阶段检索：Milvus 向量召回 Top-20（相似度阈值 0.3）→ `bge-reranker-v2-m3` 精排 Top-4
- Rerank 服务异常时自动降级为向量召回的前 4 个结果，保证服务可用
- **多轮查询改写**：`HistoryAwareQueryTransformer` 结合对话历史，将指代性追问（如"它的第二点是什么"）用 LLM 改写成独立完整查询后再检索，改写失败自动降级为原始查询
- 自定义 `ContextualQueryAugmenter` 增强模板，允许模型结合对话历史与知识库上下文共同回答
- 文档片段携带 `【文档标题：xxx】` 前缀，支持按文件名语义检索
- 检索流程实时推送到前端（"正在检索知识库..." 等工具调用事件）

### 🔖 来源引用展示
- 回答下方自动展示「📚 参考来源」蓝色气泡，列出本次检索命中的文档名（去重、按相关性排序）
- 相关度门槛过滤：相似度低于 0.5 的片段不作为来源展示，避免闲聊误召回内容误导用户
- 纯工具调用 / 闲聊回答不显示来源气泡

### 🦾 工具调用（Function Calling）
| 工具 | 功能 |
|---|---|
| `searchKnowledge` | 在知识库中检索相关文档片段 |
| `getDocumentContent` | 按文件名获取指定文档完整内容 |
| `getKnowledgeBaseStats` | 统计文档数量、片段总数与文档清单 |
| `getCurrentDateTime` | 获取服务器当前日期时间 |
| `calculate` | 精确计算数学表达式（SpEL 表达式引擎） |

### 📄 文档管理
- 基于 Apache Tika 解析 PDF / Word / TXT / Markdown 等多种格式
- `TokenTextSplitter` 按 Token 切分文档片段，嵌入为 1024 维向量（BAAI/bge-m3）
- 支持文档上传、按文件名删除（元数据过滤）、文档清单查询
- 重复上传同名文档自动覆盖旧版本

### 🖥️ Web 前端
- 内置单页聊天界面（`static/index.html`）
- 流式打字机效果、工具调用状态提示
- **左侧会话侧边栏**：会话列表、切换会话回显历史、新建会话、删除会话
- 页面内直接上传文档、管理知识库文档列表

## 技术栈

| 组件 | 技术 |
|---|---|
| 框架 | Spring Boot 3.5.16 / Spring AI 1.0.9 / Java 21 |
| 大模型 | DeepSeek `deepseek-chat` |
| 向量嵌入 | SiliconFlow `BAAI/bge-m3`（1024 维） |
| 重排序 | SiliconFlow `bge-reranker-v2-m3` |
| 向量数据库 | Milvus 2.4（Docker：etcd + MinIO + standalone） |
| 对话记忆 | Redis（端口 6380，自定义 Repository 持久化） |
| 文档解析 | Apache Tika |

## 架构流程

```
用户提问
   │
   ▼
PromptChatMemoryAdvisor ──► 注入 Redis 中的对话历史（最近 20 条）
   │
   ▼
RetrievalAugmentationAdvisor
   ├─ Milvus 向量召回 Top-20（相似度 ≥ 0.3）
   └─ bge-reranker-v2-m3 精排 Top-4 ──► 失败自动降级
   │
   ▼
DeepSeek Chat Model ◄── 必要时调用 Tools（检索/统计/时间/计算）
   │
   ▼
SSE 流式返回前端（含工具调用事件）
```

## 快速开始

### 1. 启动基础设施

确保 Docker Desktop 运行中，然后启动 Milvus 与 Redis：

```bash
docker compose up -d
```

### 2. 配置 API Key

在 `src/main/resources` 下创建 `application-local.yml`（已被 .gitignore 忽略，不会提交密钥）：

```yaml
deepseek:
  api-key: 你的DeepSeek密钥

siliconflow:
  api-key: 你的SiliconFlow密钥
```

### 3. 启动应用

```bash
mvn spring-boot:run
```

### 4. 访问

浏览器打开 <http://localhost:8080>，上传文档构建知识库后即可对话。

## REST API

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/chat?msg=&conversationId=` | 同步对话 |
| GET | `/chat/stream?msg=&conversationId=` | SSE 流式对话（含工具调用与来源事件） |
| GET | `/conversations` | 会话列表（标题 + 消息数 + 最后预览） |
| GET | `/conversations/{id}/messages` | 历史消息回显 |
| DELETE | `/conversations/{id}` | 删除会话（同时清 Redis 持久化） |
| POST | `/knowledge/upload?file=` | 上传文档入库 |
| GET | `/knowledge/documents` | 查询文档清单 |
| DELETE | `/knowledge/document?fileName=` | 按文件名删除文档 |

## 项目结构

```
src/main/java/com/example/java_ai/
├── AiConfig.java                  # ChatClient / ChatMemory / RAG Advisor 配置（含来源收集）
├── ChatController.java            # 对话接口（同步 + SSE 流式）
├── ConversationController.java    # 会话管理接口（列表 / 历史回显 / 删除）
├── HistoryAwareQueryTransformer.java # 多轮查询改写（LLM 重写追问为独立查询）
├── KnowledgeBaseController.java   # 知识库管理接口
├── KnowledgeBaseService.java      # 文档解析、切分、入库、删除、查询
├── KnowledgeTools.java            # Function Calling 工具集
├── RerankService.java             # bge-reranker 精排服务（含降级）
├── RedisChatMemoryRepository.java # Redis 对话记忆持久化（7 天 TTL）
├── ToolCallTracker.java           # 工具调用事件推送（SSE）
└── JavaAiApplication.java         # 启动类
```

## 关键设计说明

- **记忆与 RAG 共存**：`MessageChatMemoryAdvisor` 注入的消息列表会被 RAG 流程重建时丢弃，因此改用 `PromptChatMemoryAdvisor` 将历史写入 system 消息文本，两条链路互不干扰
- **RAG 增强模板变量**：`ContextualQueryAugmenter` 自定义模板必须使用框架注入的 `{query}` 与 `{context}` 变量名，使用其他名称（如 `{query_context}`）会导致模板渲染校验失败
- **密钥安全**：API Key 通过 `application-local.yml` 多 Profile 机制加载，该文件已加入 .gitignore
- **文件名可检索**：切分后的片段文本头部追加 `【文档标题：{fileName}】`，使"按名称找文档"这类查询也能通过语义匹配命中
