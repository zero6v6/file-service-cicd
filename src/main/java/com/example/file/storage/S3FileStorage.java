package com.example.file.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.time.Duration;
import java.util.Optional;

/**
 * S3 저장소. prod 프로파일에서만 올라옴.
 *
 * 버킷 이름은 환경 변수 APP_S3_BUCKET, 접두사는 uploads/ 고정.
 * 접두사가 IAM 정책(<student_id>-app-policy 의 uploads/*)과 같아야 함.
 *
 * 자격 증명은 코드에 없음. S3Client 가 기본 자격 증명 체인으로 찾음.
 *   EC2 → 인스턴스 프로파일(역할)
 *   내 PC → 환경 변수나 ~/.aws (이 과정에서는 쓰지 않음)
 */
@Component
@Profile("prod")
public class S3FileStorage implements FileStorage {

    private static final Logger log = LoggerFactory.getLogger(S3FileStorage.class);

    /** IAM 정책의 Resource 와 짝이 되는 접두사 */
    public static final String PREFIX = "uploads/";

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;

    public S3FileStorage(S3Client s3, S3Presigner presigner, @Value("${app.s3.bucket}") String bucket) {
        this.s3 = s3;
        this.presigner = presigner;
        this.bucket = bucket;
        log.info("S3 저장소 준비 : s3://{}/{}", bucket, PREFIX);
    }

    @Override
    public String kind() {
        return "s3";
    }

    @Override
    public String keyFor(String fileName) {
        return PREFIX + fileName;
    }

    @Override
    public String save(MultipartFile file, String key) throws IOException {
        String objectKey = key;
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .contentType(file.getContentType())
                .contentLength(file.getSize())
                .build();
        // fromInputStream → 파일을 메모리에 다 올리지 않고 스트림으로 보냄. 길이를 함께 줘야 함
        s3.putObject(request, RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
        log.info("S3 저장 : s3://{}/{} ({} bytes)", bucket, objectKey, file.getSize());
        return objectKey;
    }

    @Override
    public Resource load(String key) throws IOException {
        try {
            GetObjectRequest request = GetObjectRequest.builder().bucket(bucket).key(key).build();
            // getObject 의 응답 스트림을 그대로 HTTP 응답으로 흘려보냄 (앱 경유 다운로드)
            return new InputStreamResource(s3.getObject(request));
        } catch (NoSuchKeyException e) {
            throw new FileNotFoundException(key);
        }
    }

    @Override
    public void delete(String key) throws IOException {
        // S3 삭제는 없는 키여도 성공으로 돌려줌. 버전 관리 버킷이면 삭제 마커가 얹힘
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        log.info("S3 삭제 : s3://{}/{}", bucket, key);
    }

    /**
     * 프리사인 GET URL. 서명자(이 인스턴스의 역할)의 권한을 ttl 동안 빌려줌.
     * 응답 헤더를 서명에 포함해 브라우저가 원본 파일명으로 저장하게 함.
     */
    @Override
    public Optional<String> downloadUrl(String key, String originalName, Duration ttl) {
        String disposition = "attachment; filename*=UTF-8''" + URLEncoder.encode(originalName, StandardCharsets.UTF_8)
                .replace("+", "%20");
        GetObjectRequest get = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .responseContentDisposition(disposition)
                .build();
        GetObjectPresignRequest presign = GetObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .getObjectRequest(get)
                .build();
        String url = presigner.presignGetObject(presign).url().toString();
        log.info("프리사인 GET : {} ({}초)", key, ttl.toSeconds());
        return Optional.of(url);
    }

    /**
     * 프리사인 PUT URL. 브라우저가 이 주소로 파일 본문을 PUT 하면 S3 에 바로 저장됨.
     * Content-Type 을 서명에 넣었으므로 브라우저도 같은 Content-Type 헤더를 보내야 함.
     * 앱 서버는 본문을 전혀 거치지 않음 → 07 교안의 "앱 경유 vs 직접" 비교 포인트.
     */
    @Override
    public Optional<String> uploadUrl(String key, String contentType, Duration ttl) {
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .build();
        PutObjectPresignRequest presign = PutObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .putObjectRequest(put)
                .build();
        String url = presigner.presignPutObject(presign).url().toString();
        log.info("프리사인 PUT : {} ({}초)", key, ttl.toSeconds());
        return Optional.of(url);
    }

    /** headObject → 본문을 받지 않고 메타데이터만 읽음. 직접 업로드가 끝났는지 확인할 때 씀 */
    @Override
    public Optional<Long> size(String key) throws IOException {
        try {
            return Optional.of(s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build())
                    .contentLength());
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        }
    }
}
