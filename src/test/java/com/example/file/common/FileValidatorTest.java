package com.example.file.common;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * FileValidator 단위 테스트. 스프링 컨테이너 없이 new 로 만들어 바로 검사함.
 *
 * MockMultipartFile : 실제 HTTP 업로드 없이 MultipartFile 을 흉내 내는 테스트용 객체
 *   new MockMultipartFile(폼 필드 이름, 원본 파일 이름, Content-Type, 바이트 본문)
 */
class FileValidatorTest {

    private final FileValidator validator = new FileValidator(10 * 1024 * 1024);

    @Test
    void 허용_확장자는_통과함() {
        MockMultipartFile file = new MockMultipartFile("file", "note.txt", "text/plain", "hello".getBytes());
        assertDoesNotThrow(() -> validator.validate(file));
    }

    @Test
    void 빈_파일은_거부함() {
        MockMultipartFile file = new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]);
        assertThrows(IllegalArgumentException.class, () -> validator.validate(file));
    }

    @Test
    void 경로_문자는_거부함() {
        MockMultipartFile file = new MockMultipartFile("file", "../etc/passwd.txt", "text/plain", "x".getBytes());
        assertThrows(IllegalArgumentException.class, () -> validator.validate(file));
    }

    @Test
    void 확장자는_소문자로_뽑음() {
        assertEquals("png", FileValidator.extensionOf("Photo.PNG"));
        assertEquals("", FileValidator.extensionOf("noext"));
    }

    // TODO 1 · validateExtension 을 작성한 뒤 아래 주석을 풀면 통과해야 함
    @Test
    void 허용하지_않는_확장자는_거부함() {
        MockMultipartFile file = new MockMultipartFile("file", "run.exe", "application/octet-stream", new byte[]{1});
        assertThrows(IllegalArgumentException.class, () -> validator.validate(file));
    }

    // TODO 2 · validateSize 를 작성한 뒤 아래 주석을 풀면 통과해야 함
    @Test
    void 상한을_넘는_파일은_거부함() {
        FileValidator small = new FileValidator(4);
        MockMultipartFile file = new MockMultipartFile("file", "big.txt", "text/plain", "12345".getBytes());
        assertThrows(IllegalArgumentException.class, () -> small.validate(file));
    }
}
