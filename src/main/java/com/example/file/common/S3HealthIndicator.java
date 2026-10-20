package com.example.file.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;

/**
 * S3 연결 확인 항목. /actuator/health 의 s3 로 표시됨.
 *
 * headBucket 은 객체를 읽지 않고 버킷 존재와 권한만 확인함 (s3:ListBucket 필요).
 * ALB 상태 검사가 몇 초마다 부르므로 가벼운 호출만 둠.
 *
 * 버킷 이름은 show-details: never 이면 밖으로 나가지 않음.
 */
@Component
@Profile("prod")
public class S3HealthIndicator implements HealthIndicator {

    private final S3Client s3;
    private final String bucket;

    public S3HealthIndicator(S3Client s3, @Value("${app.s3.bucket}") String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    @Override
    public Health health() {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            return Health.up().withDetail("bucket", bucket).build();
        } catch (Exception e) {
            // 403 → 역할 권한 부족, 404 → 버킷 이름 오타, 연결 실패 → 엔드포인트/NAT 없음
            return Health.down().withDetail("사유", e.getClass().getSimpleName()).build();
        }
    }
}
