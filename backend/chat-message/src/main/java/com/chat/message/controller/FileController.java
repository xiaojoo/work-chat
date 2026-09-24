package com.chat.message.controller;

import com.chat.message.config.AuthFilter;
import com.chat.message.service.FileStorage;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 上传/下载走消息服务的 REST，路径挂在 /api/message 前缀下：
 * 这样网页端的 vite 代理规则（/api/message -> 8083）不用改就能命中。
 */
@RestController
@RequestMapping("/api/message/file")
public class FileController {

    private static final String FALLBACK_TYPE = "application/octet-stream";

    private final FileStorage files;

    public FileController(FileStorage files) {
        this.files = files;
    }

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> upload(
            @RequestAttribute(AuthFilter.ATTR_USER_ID) Long userId,
            @RequestParam("file") MultipartFile file) throws Exception {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "文件为空"));
        }
        String key = files.store(file);
        String name = file.getOriginalFilename() == null || file.getOriginalFilename().isBlank()
                ? key : file.getOriginalFilename();
        String type = file.getContentType() == null ? FALLBACK_TYPE : file.getContentType();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fileId", key);
        body.put("name", name);
        body.put("size", file.getSize());
        body.put("contentType", type);
        body.put("url", "/api/message/file/" + key);
        return ResponseEntity.ok(body);
    }

    /** 鉴权后才能取（AuthFilter 已挡在前面）；对象键本身不可枚举，所以知道键就等于有权限。 */
    @GetMapping("/{fileId}")
    public ResponseEntity<InputStreamResource> download(@PathVariable String fileId) throws Exception {
        if (!FileStorage.isValidKey(fileId) || files.sizeOf(fileId) < 0) {
            return ResponseEntity.notFound().build();
        }
        InputStream in = files.open(fileId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(files.contentTypeOf(fileId)))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=86400")
                .body(new InputStreamResource(in));
    }
}
