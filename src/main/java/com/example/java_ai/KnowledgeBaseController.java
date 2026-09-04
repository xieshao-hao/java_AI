package com.example.java_ai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/knowledge")
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    public KnowledgeBaseController(KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "上传文件为空"));
        }
        try {
            int chunks = knowledgeBaseService.ingest(file);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("fileName", file.getOriginalFilename());
            result.put("chunks", chunks);
            result.put("message", "文档入库成功");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "文档解析或入库失败：" + e.getMessage()));
        }
    }

    @GetMapping("/documents")
    public List<Map<String, Object>> listDocuments() {
        return knowledgeBaseService.listDocuments();
    }

    @DeleteMapping("/document")
    public ResponseEntity<Map<String, Object>> deleteDocument(@RequestParam("fileName") String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "文件名不能为空"));
        }
        try {
            knowledgeBaseService.deleteByFileName(fileName);
            return ResponseEntity.ok(Map.of("message", "文档删除成功：" + fileName));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "文档删除失败：" + e.getMessage()));
        }
    }
}