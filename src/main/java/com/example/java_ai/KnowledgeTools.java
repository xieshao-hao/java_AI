package com.example.java_ai;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeTools {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final VectorStore vectorStore;
    private final KnowledgeBaseService knowledgeBaseService;
    private final ToolCallTracker toolCallTracker;
    private final RerankService rerankService;
    private final SpelExpressionParser parser = new SpelExpressionParser();

    public KnowledgeTools(VectorStore vectorStore, KnowledgeBaseService knowledgeBaseService,
                          ToolCallTracker toolCallTracker, RerankService rerankService) {
        this.vectorStore = vectorStore;
        this.knowledgeBaseService = knowledgeBaseService;
        this.toolCallTracker = toolCallTracker;
        this.rerankService = rerankService;
    }

    @Tool(name = "getCurrentDateTime", description = "获取服务器当前的日期和时间，当用户询问现在的时间或日期时调用")
    public String getCurrentDateTime(ToolContext toolContext) {
        notifyToolCall(toolContext, "getCurrentDateTime", "无参数");
        return LocalDateTime.now().format(FORMATTER);
    }

    @Tool(name = "getKnowledgeBaseStats", description = "获取知识库统计信息，包括文档数量、片段总数和文档清单")
    public String getKnowledgeBaseStats(ToolContext toolContext) {
        notifyToolCall(toolContext, "getKnowledgeBaseStats", "无参数");
        List<Map<String, Object>> docs = knowledgeBaseService.listDocuments();
        int totalChunks = docs.stream().mapToInt(d -> (int) d.get("chunks")).sum();
        String names = docs.stream().map(d -> String.valueOf(d.get("fileName")))
                .reduce((a, b) -> a + "、" + b).orElse("无");
        return "知识库共 " + docs.size() + " 篇文档、" + totalChunks + " 个片段，文档清单：" + names;
    }

    @Tool(name = "searchKnowledge", description = "在知识库中检索与查询语句相关的文档片段并返回原文，当需要回答具体知识性问题时调用")
    public String searchKnowledge(@ToolParam(description = "检索用的查询语句") String query,
                                  ToolContext toolContext) {
        notifyToolCall(toolContext, "searchKnowledge", query);
        // 两阶段检索：向量召回 Top-20 → Rerank 精排 Top-4
        List<Document> candidates = vectorStore.similaritySearch(
                SearchRequest.builder().query(query).topK(20).similarityThreshold(0.3).build());
        if (candidates == null || candidates.isEmpty()) {
            return "知识库中未检索到与【" + query + "】相关的内容";
        }
        List<Document> results = rerankService.rerank(query, candidates, 4);
        StringBuilder sb = new StringBuilder();
        for (Document doc : results) {
            String text = doc.getText();
            sb.append(text != null && text.length() > 800 ? text.substring(0, 800) : text)
                    .append("\n---\n");
        }
        return sb.toString();
    }

    @Tool(name = "getDocumentContent", description = "按文件名获取知识库中指定文档的完整内容，当用户询问某个文档里写了什么、要求输出某个文档原文或按名称查看文档时调用")
    public String getDocumentContent(@ToolParam(description = "文档文件名，例如 AI训练指令.md") String fileName,
                                     ToolContext toolContext) {
        notifyToolCall(toolContext, "getDocumentContent", fileName);
        String content = knowledgeBaseService.getDocumentContent(fileName);
        return content == null ? "知识库中未找到名为【" + fileName + "】的文档" : content;
    }

    @Tool(name = "calculate", description = "精确计算数学表达式的值，当用户需要进行数值计算时调用，不要自己心算")
    public String calculate(@ToolParam(description = "数学表达式，例如 125 * 44 + 7") String expression,
                            ToolContext toolContext) {
        notifyToolCall(toolContext, "calculate", expression);
        if (!expression.matches("[0-9+\\-*/(). ]+")) {
            return "不支持的表达式，仅支持数字与四则运算符";
        }
        try {
            Object result = parser.parseExpression(expression).getValue();
            return expression + " = " + result;
        } catch (Exception e) {
            return "表达式计算失败：" + e.getMessage();
        }
    }

    private void notifyToolCall(ToolContext toolContext, String toolName, String args) {
        if (toolContext == null) {
            return;
        }
        Object conversationId = toolContext.getContext().get("conversationId");
        if (conversationId != null) {
            toolCallTracker.emit(conversationId.toString(), toolName, args);
        }
    }
}