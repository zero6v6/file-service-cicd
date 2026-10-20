package com.example.file.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 디스크 저장소. local 프로파일에서만 올라옴.
 *
 * 경로는 application-local.yml 의 file.local.path.
 *   Windows : C:/CE/98.data/uploads
 *   macOS   : ~/CE/98.data/uploads
 * 기본값은 프로젝트 아래 ./data/uploads (저장소에는 올리지 않음).
 */
@Component
@Profile("local")
public class LocalFileStorage implements FileStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalFileStorage.class);

    private final Path root;

    public LocalFileStorage(@Value("${file.local.path:./data/uploads}") String path) throws IOException {
        // Paths.get → OS 별 경로 구분자를 알아서 처리함 (Windows \ , macOS /)
        this.root = Paths.get(path).toAbsolutePath().normalize();
        Files.createDirectories(root);
        log.info("디스크 저장소 준비 : {}", root);
    }

    @Override
    public String kind() {
        return "local";
    }

    @Override
    public String save(MultipartFile file, String key) throws IOException {
        Path target = resolve(key);
        // transferTo → 멀티파트 임시 파일을 목적지로 옮김. 메모리에 다시 올리지 않음
        file.transferTo(target);
        log.info("저장 : {} ({} bytes)", target, file.getSize());
        return key;
    }

    @Override
    public Resource load(String key) throws IOException {
        Path target = resolve(key);
        if (!Files.exists(target)) {
            throw new java.io.FileNotFoundException(key);
        }
        return new PathResource(target);
    }

    @Override
    public void delete(String key) throws IOException {
        // deleteIfExists → 없으면 false 를 돌려줄 뿐 예외가 없음
        boolean deleted = Files.deleteIfExists(resolve(key));
        log.info("삭제 : {} ({})", key, deleted ? "삭제됨" : "없음");
    }

    /** 경로 탈출(../) 방지. key 가 root 밖을 가리키면 거부함 */
    private Path resolve(String key) {
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("잘못된 파일 키: " + key);
        }
        return target;
    }
}
