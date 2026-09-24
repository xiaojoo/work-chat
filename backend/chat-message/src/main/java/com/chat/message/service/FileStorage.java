package com.chat.message.service;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 文件与图片存 MinIO，WS 帧里只放对象键 —— 网关只做 JSON 扇出，不过媒体字节。
 */
@Service
public class FileStorage {

    /** 对象键一律由我们生成；下载侧按这个白名单挡掉路径穿越与外来的键。 */
    private static final Pattern OBJECT_KEY = Pattern.compile("^[0-9a-f]{32}\\.[a-z0-9]{1,8}$");

    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private final MinioClient minio;
    private final String bucket;

    public FileStorage(MinioClient minio, @Value("${minio.bucket}") String bucket) throws Exception {
        this.minio = minio;
        this.bucket = bucket;
        if (!minio.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            minio.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }

    public static boolean isValidKey(String key) {
        return key != null && OBJECT_KEY.matcher(key).matches();
    }

    /** 返回对象键。UUID 而不是雪花号段：它不是表主键，而且不可枚举这一条更重要。 */
    public String store(MultipartFile file) throws Exception {
        String key = UUID.randomUUID().toString().replace("-", "") + extensionOf(file.getOriginalFilename());
        minio.putObject(PutObjectArgs.builder()
                .bucket(bucket)
                .object(key)
                .stream(file.getInputStream(), file.getSize(), -1)
                .contentType(file.getContentType() == null ? DEFAULT_CONTENT_TYPE : file.getContentType())
                .build());
        return key;
    }

    public InputStream open(String key) throws Exception {
        return minio.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build());
    }

    /** 撤回带文件的消息时把对象一并删掉，否则桶里只会留下没人引用的孤儿 */
    public void delete(String key) throws Exception {
        if (!isValidKey(key)) {
            return;
        }
        minio.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
    }

    public long sizeOf(String key) {
        try {
            return minio.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build()).size();
        } catch (Exception e) {
            return -1L;
        }
    }

    public String contentTypeOf(String key) {
        try {
            String ct = minio.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build()).contentType();
            return ct == null ? DEFAULT_CONTENT_TYPE : ct;
        } catch (Exception e) {
            return DEFAULT_CONTENT_TYPE;
        }
    }

    /** 只留 . 后 1-8 位纯字母数字，防 "x.php."、超长后缀、以及把分隔符带进键里。 */
    private static String extensionOf(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        String ext = name.substring(dot + 1).toLowerCase();
        return ext.matches("^[a-z0-9]{1,8}$") ? "." + ext : "";
    }
}
