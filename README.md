# Java AI 智能知识库助手

基于 Spring Boot 3.5 + Spring AI 1.0.9 构建的**意图路由 + 多 Agent 智能对话应用**，融合 **意图识别路由**、**RAG（检索增强生成）**、**Function Calling（工具调用）** 与 **跨会话持久化对话记忆**，支持上传私有文档构建个人知识库。

## 功能特性

### 💬 智能对话
- 接入 DeepSeek（`deepseek-chat`）大模型，OpenAI 兼容协议
- 支持 SSE 流式输出与同步调用两种模式（`/chat`、`/chat/stream`）
- 会话隔离：前端通过 `conversationId` 区分不同会话，存储于 sessionStorage，刷新页面不丢失

### 🧭 意图路由与多 Agent 分发
- **三段式路由**：规则快筛（<1ms，确定性短指令）→ Redis 缓存命中（同问题不重复分类）→ LLM 意图分类（置信度阈值 0.8）
- **改写与分类合一**：LLM 路由一次调用同时完成多轮指代消解（"它的第二点是什么" → 独立完整查询）与意图分类，延迟减半
- **按意图分发到独立 Agent**：闲聊走无 RAG 最短链路（零检索成本），知识库问题走完整 RAG 链路
- **路由永不失败**：LLM 异常 / 置信度不足 / 无对应 Agent 时统一降级到知识库 Agent 兜底，形成全链路降级体系
- 路由决策结果缓存（Redis TTL 1 小时），并输出结构化日志：输入 → 改写 → 意图 → 置信度 → 来源 → 耗时（M4 可观测性埋点）

### 🧠 对话记忆持久化
- 自定义 `RedisChatMemoryRepository`，将对话历史持久化到 Redis（TTL 7 天，活跃会话自动续期）
- 滑动窗口策略：`MessageWindowChatMemory` 每次注入最近 20 条消息（约 10 轮对话）
- 采用 `PromptChatMemoryAdvisor` 将历史注入 system 消息，确保历史在 RAG 流程中不被丢弃
- 只持久化 USER / ASSISTANT / SYSTEM 消息，工具调用中间消息不跨会话保留
- **应用重启后会话可恢复**，模型依然记得之前的对话内容

### 📚 RAG 知识库检索
- 两阶段检索：Milvus 向量召回 Top-20（相似度阈值 0.3）→ `bge-reranker-v2-m3` 精排 Top-4
- Rerank 服务异常时自动降级为向量召回的前 4 个结果，保证服务可用
- **多轮查询改写**：查询改写已上移到意图路由层（改写 + 意图分类合并为一次 LLM 调用），三个 Agent 共享改写后的独立完整查询，RAG 层不再重复改写
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
IntentRouter 意图路由（三段式）
   ├─ ① 规则快筛（<1ms，确定性短指令）
   ├─ ② Redis 缓存命中（TTL 1h）
   └─ ③ LLM 改写 + 意图分类（一次调用，置信度 ≥ 0.8）── 失败/低分 ──► 知识库兜底
   │
   ▼
AgentDispatcher 按意图分发
   ├─ CHITCHAT ──► ChatAgent（无 RAG 最短链，仅记忆）
   └─ KNOWLEDGE ──► KbAgent（完整 RAG 链，见下）
         │
         ▼
      PromptChatMemoryAdvisor ──► 注入 Redis 对话历史（最近 20 条）
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
├── AiConfig.java                  # KbAgent 专用 ChatClient / ChatMemory / RAG Advisor 配置（含来源收集）
├── ChatController.java            # 对话接口（同步 + SSE 流式），路由 → 分发 → Agent
├── ConversationController.java    # 会话管理接口（列表 / 历史回显 / 删除）
├── HistoryAwareQueryTransformer.java # 多轮查询改写（已被路由层改写替代，待删除）
├── KnowledgeBaseController.java   # 知识库管理接口
├── KnowledgeBaseService.java      # 文档解析、切分、入库、删除、查询
├── KnowledgeTools.java            # Function Calling 工具集
├── RerankService.java             # bge-reranker 精排服务（含降级）
├── RedisChatMemoryRepository.java # Redis 对话记忆持久化（7 天 TTL）
├── ToolCallTracker.java           # 工具调用事件推送（SSE）
├── router/                        # 意图路由层
│   ├── RouteIntent.java           # 意图枚举（闲聊/设备/工单/知识库）
│   ├── RouteDecision.java         # 路由决策（意图/置信度/改写查询/来源/耗时）
│   ├── LlmRouteResult.java        # LLM 结构化输出（改写 + 分类合一）
│   ├── RuleIntentMatcher.java     # 规则快筛（确定性短指令）
│   ├── RouterConfig.java          # 意图分类器 ChatClient（温度 0）
│   └── IntentRouter.java          # 路由编排：规则 → 缓存 → LLM → 兜底
├── agent/                         # Agent 接口层
│   ├── Agent.java                 # Agent 抽象接口（声明意图 + 处理入口）
│   ├── AgentContext.java          # Agent 输入上下文（含改写后查询）
│   ├── AgentDispatcher.java       # 意图 → Agent 分发器（自动注册，双保险兜底）
│   ├── ChatAgent.java             # 闲聊 Agent（无 RAG 最短链）
│   └── KbAgent.java               # 知识库 Agent（完整 RAG 链）
└── JavaAiApplication.java         # 启动类
```

## 关键设计说明

- **意图路由三段式**：规则快筛（零成本处理确定性短指令）→ Redis 缓存（重复问题 <10ms）→ LLM 改写 + 分类合一（延迟减半）。规则层"宁可漏放不可错放"——错放的输入会绕过 LLM 校验，是路由质量的最大杀手
- **路由永不失败（全链路降级体系）**：LLM 异常 / 意图置信度 < 0.8 / 分发层无对应 Agent，统一降级到知识库 Agent；与 rerank 失败降级 Top-4 同一设计哲学：宁可能力降级，不可服务中断
- **查询改写上移**：多轮指代消解从 RAG 层上移到路由层（与意图分类合并为一次 LLM 调用），三个 Agent 都受益于干净输入；RAG 层不再重复改写，避免双重 LLM 调用与语义二次漂移
- **记忆策略按链路差异化**：KbAgent 因 RAG 消息重建问题使用 `PromptChatMemoryAdvisor`（历史注入 system 消息），ChatAgent 无 RAG 故可用 `MessageChatMemoryAdvisor`；两者共享同一个 `ChatMemory` Bean，保证会话历史跨 Agent 互通
- **记忆与 RAG 共存**：`MessageChatMemoryAdvisor` 注入的消息列表会被 RAG 流程重建时丢弃，因此知识库链路改用 `PromptChatMemoryAdvisor` 将历史写入 system 消息文本，两条链路互不干扰
- **RAG 增强模板变量**：`ContextualQueryAugmenter` 自定义模板必须使用框架注入的 `{query}` 与 `{context}` 变量名，使用其他名称（如 `{query_context}`）会导致模板渲染校验失败
- **Agent 开闭原则**：新增 Agent 只需实现 `Agent` 接口并标注 `@Component`，`AgentDispatcher` 通过 Spring 注入 `List<Agent>` 自动注册，分发器代码零修改
- **密钥安全**：API Key 通过 `application-local.yml` 多 Profile 机制加载，该文件已加入 .gitignore
- **文件名可检索**：切分后的片段文本头部追加 `【文档标题：{fileName}】`，使"按名称找文档"这类查询也能通过语义匹配命中
