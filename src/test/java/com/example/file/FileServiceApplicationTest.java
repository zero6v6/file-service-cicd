package com.example.file;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** local 프로파일로 컨텍스트가 뜨는지만 확인함. S3 · AWS 자격 증명이 필요 없음 */
@SpringBootTest
@ActiveProfiles("local")
class FileServiceApplicationTest {

    @Test
    void contextLoads() {
    }
}
