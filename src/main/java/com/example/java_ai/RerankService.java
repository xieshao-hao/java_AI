package com.example.java_ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * 重排序服务：调用 SiliconFlow bge-reranker-v2-m3 模型对候选文档进行精排。
 * 与向量召回（Bi-Encoder）不同，Rerank 使用 Cross-Encoder 逐对深度匹配，精度更高。
 */
@Service
public class RerankService {

    private final RestClient restClient;
    private final String model;

    public RerankService(@Value("${app.rerank.base-url}") String baseUrl,
                         @Value("${app.rerank.api-key}") String apiKey,
                         @Value("${app.rerank.model}") String model) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
        this.model = model;
    }

    /**
     * 对候选文档列表进行重排序，返回 topN 个最相关的文档。
     * 如果候选数不足 topN 或调用失败，降级为返回原始候选的前 topN 个。
     */
    @SuppressWarnings("unchecked")
    public List<Document> rerank(String query, List<Document> candidates, int topN) {
        if (candidates.size() <= topN) {
            return candidates;
        }
        List<String> documents = candidates.stream().map(Document::getText).toList();
        Map<String, Object> response;
        try {
            response = restClient.post()
                    .uri("/v1/rerank")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "model", model,
                            "query", query,
                            "documents", documents,
                            "top_n", topN,
                            "return_documents", false))
                    .retrieve()
                    .body(Map.class);
        } catch (Exception e) {
            // 降级：rerank 服务不可用时，取向量召回的前 topN 个
            return candidates.subList(0, topN);
        }
        List<Map<String, Object>> results = response == null ? null
                : (List<Map<String, Object>>) response.get("results");
        if (results == null || results.isEmpty()) {
            return candidates.subList(0, topN);
        }
        List<Document> reranked = new ArrayList<>();
        for (Map<String, Object> item : results) {
            int index = ((Number) item.get("index")).intValue();
            if (index >= 0 && index < candidates.size()) {
                reranked.add(candidates.get(index));
            }
        }
        return reranked;
    }
}
