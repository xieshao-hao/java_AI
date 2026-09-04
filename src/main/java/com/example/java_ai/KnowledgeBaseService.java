package com.example.java_ai;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class KnowledgeBaseService {

    private final VectorStore vectorStore;

    public KnowledgeBaseService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public int ingest(MultipartFile file) throws IOException {
        TikaDocumentReader reader = new TikaDocumentReader(file.getResource());
        List<Document> documents = reader.get();
        if (documents.isEmpty()) {
            return 0;
        }
        String fileName = file.getOriginalFilename() == null ? "未命名文档" : file.getOriginalFilename();
        String finalFileName = fileName;
        documents.forEach(doc -> doc.getMetadata().put("fileName", finalFileName));

        TokenTextSplitter splitter = new TokenTextSplitter();
        List<Document> chunks = splitter.apply(documents);
        if (chunks.isEmpty()) {
            return 0;
        }
        // 把文件名写进片段文本，使文件名也能参与语义检索
        chunks = chunks.stream()
                .map(ch -> new Document("【文档标题：" + finalFileName + "】\n" + ch.getText(), ch.getMetadata()))
                .toList();
        deleteByFileName(fileName);
        vectorStore.add(chunks);
        return chunks.size();
    }

    public void deleteByFileName(String fileName) {
        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        vectorStore.delete(builder.eq("fileName", fileName).build());
    }

    public String getDocumentContent(String fileName) {
        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        List<Document> docs = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(" ")
                        .topK(100)
                        .similarityThreshold(0)
                        .filterExpression(builder.eq("fileName", fileName).build())
                        .build());
        if (docs == null || docs.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Document doc : docs) {
            String text = doc.getText();
            if (text != null && !text.isBlank()) {
                sb.append(text).append("\n");
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    public List<Map<String, Object>> listDocuments() {
        List<Document> all = vectorStore.similaritySearch(
                SearchRequest.builder().query(" ").topK(1000).similarityThreshold(0).build());
        if (all == null) {
            return List.of();
        }
        Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
        for (Document doc : all) {
            String name = String.valueOf(doc.getMetadata().getOrDefault("fileName", "未知文档"));
            Map<String, Object> item = grouped.computeIfAbsent(name, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("fileName", k);
                m.put("chunks", 0);
                return m;
            });
            item.put("chunks", (int) item.get("chunks") + 1);
        }
        return new ArrayList<>(grouped.values());
    }
}