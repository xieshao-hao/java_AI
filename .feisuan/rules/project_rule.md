
# 开发规范指南

为保证代码质量、可维护性、安全性与可扩展性，请在开发过程中严格遵循以下规范。本文档基于项目实际配置进行了定制化调整。

## 一、项目基本信息

- **项目名称**：Java_ai
- **工作目录**：`C:\Users\Administrator\Desktop\AI\Java_ai`
- **代码作者**：Administrator
- **当前版本**：0.0.1-SNAPSHOT

## 二、技术栈要求

- **主框架**：Spring Boot 3.5.16
- **语言版本**：Java 21
- **构建工具**：Maven
- **核心依赖**：
  - `spring-ai-starter-model-openai` (v1.0.9)：用于对接 DeepSeek 等 OpenAI 协议接口
  - `spring-ai-starter-vector-store-milvus` (v1.0.9)：向量数据库存储
  - `spring-ai-rag` & `spring-ai-advisors-vector-store`：RAG 检索增强生成功能
  - `spring-ai-tika-document-reader`：文档解析（PDF/Word等）

> **注意**：当前项目专注于 AI 与向量检索，未引入 JPA，数据持久化主要依赖向量数据库 Milvus。

## 三、项目目录结构

```text
Java_ai
├── src
│   ├── main
│   │   ├── java
│   │   │   └── com
│   │   │       └── example
│   │   │           └── java_ai
│   │   ├── resources
│   │   │   ├── static
│   │   │   ├── templates
│   │   │   └── application.yml
│   └── test
│       └── java
│           └── com
│               └── example
│                   └── java_ai
└── volumes
    ├── etcd                  # Milvus 依赖的 Etcd 数据
    ├── milvus                # Milvus 向量数据库数据
    └── minio                 # MinIO 存储数据
```

## 四、分层架构规范

鉴于项目特性，架构分层调整如下：

| 层级 | 职责说明 | 开发约束与注意事项 |
|---|---|---|
| **Controller** | 处理 HTTP 请求，暴露 AI 交互接口 | 仅负责参数校验与响应封装，不处理复杂业务逻辑 |
| **Service** | 实现 AI 业务逻辑（对话、RAG、文档处理） | 封装 `ChatClient`、`VectorStore` 调用；处理文档切分与向量化逻辑 |
| **VectorStore** | 向量数据库交互 | 通过 Spring AI 注入的 `VectorStore` 接口操作 Milvus |
| **Config** | 配置类 | 配置 AI 模型参数、RAG Advisor、重排序模型等 Bean |

### 接口与实现分离

- 所有接口实现类需放在接口所在包下的 `impl` 子包中。

## 五、安全与配置规范

### 敏感信息管理

- **严禁在 `application.yml` 中硬编码 API Key**。
- 当前配置中 `api-key` 直接暴露，**必须**修改为环境变量或配置中心引用：
  ```yaml
  # 错误示范 (当前配置)
  api-key: sk-265506d2bc244244a3e4faec2ff382a3
  # 正确示范
  api-key: ${DEEPSEEK_API_KEY}
  ```

### 输入校验

- 使用 `@Valid` 与 JSR-303 校验注解（`jakarta.validation.constraints.*`）。
- 对用户输入的 Prompt 进行长度限制，防止资源滥用。

## 六、代码风格规范

### 命名规范

| 类型 | 命名方式 | 示例 |
|---|---|---|
| 类名 | UpperCamelCase | `ChatService`, `DocumentController` |
| 方法/变量 | lowerCamelCase | `loadDocument()`, `embeddingModel` |
| 常量 | UPPER_SNAKE_CASE | `MAX_TOKEN_LIMIT` |

### 注释规范

- **语言要求**：注释必须使用**中文**（用户第一语言）。
- 所有类、接口、公共方法需添加 **Javadoc** 注释。
- 示例：
  ```java
  /**
   * 文档处理服务
   * 负责将文档读取、切分并向量化存储
   * @author Administrator
   */
  public class DocumentService {
      // ...
  }
  ```

### 类型命名规范

| 后缀 | 用途说明 | 示例 |
|---|---|---|
| DTO | 数据传输对象 | `ChatRequestDTO` |
| VO | 视图展示对象 | `ChatResponseVO` |
| Config | 配置类 | `MilvusConfig` |

### 实体类简化

- 当前 `pom.xml` 未包含 Lombok。
- 如需使用 `@Data` 等注解，请在 `pom.xml` 中添加 `lombok` 依赖。
- 若不使用 Lombok，请使用 IDE 生成 Getter/Setter 方法。

## 七、AI 业务开发规范

### RAG 流程规范

1. **文档读取**：使用 `TikaDocumentReader` 读取文件。
2. **文本切分**：使用 `TokenTextSplitter` 进行合理分块。
3. **向量化**：调用 `EmbeddingModel` 将文本转为向量。
4. **存储**：存入 Milvus 向量库。
5. **检索增强**：使用 `QuestionAnswerAdvisor` 或自定义 Advisor 进行检索增强。

### 模型调用

- 注意区分 Chat 模型（DeepSeek）与 Embedding 模型（BAAI/bge-m3）的配置。
- 注意 `base-url` 的配置，Spring AI 会自动拼接路径，无需手动添加 `/v1/chat/completions`。

## 八、日志规范

- 使用 `@Slf4j`（需添加 Lombok）或 `LoggerFactory` 获取日志对象。
- 日志级别配置：
  - 开发环境：`org.springframework.ai: DEBUG`
  - 生产环境：`org.springframework.ai: INFO`
- 禁止使用 `System.out.println`。

## 九、编码原则总结

| 原则 | 说明 |
|---|---|
| **SOLID** | 高内聚、低耦合，增强可维护性与可扩展性 |
| **DRY** | 避免重复代码，提高复用性 |
| **KISS** | 保持代码简洁易懂 |
| **安全优先** | API Key 等敏感信息必须脱敏处理 |
