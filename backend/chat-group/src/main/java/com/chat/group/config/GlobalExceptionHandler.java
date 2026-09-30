package com.chat.group.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 这一路的业务错误就是"抛一句人话"表达的（"只有群主能解散"、"你已经在这个群里了"），
     * 所以文案要原样给回去，界面直接显示它。两处得兜住：
     * ① message 可能是 null —— 一个 NullPointerException 也是 RuntimeException，
     *    而 Map.of 不吃 null，原来的写法等于在错误处理里再抛一次，客户端拿到 500、那句人话丢了；
     * ② 数据库那类异常也是 RuntimeException 的子类，它的 message 里带完整 SQL、表名、约束名，
     *    原样回给浏览器就是把整个 schema 送出去 —— 那种单独走下面那个 handler。
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> handleRuntimeException(RuntimeException e) {
        log.warn("业务错误: {}", e.toString());
        return ResponseEntity.badRequest().body(Map.of("error", Objects.toString(e.getMessage(), "操作没成功")));
    }

    /** 唯一键冲突、外键、超时这类：真话只进日志，响应体里不给 SQL */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, String>> handleDataAccess(DataAccessException e) {
        log.error("数据层异常", e);
        return ResponseEntity.badRequest().body(Map.of("error", "这一步没存进去，请刷新后重试"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidationException(MethodArgumentNotValidException e) {
        String errors = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return ResponseEntity.badRequest().body(Map.of("error", errors));
    }
}
