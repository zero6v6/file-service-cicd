package com.example.file.api;

import com.example.file.api.dto.DownloadUrlResponse;
import com.example.file.api.dto.EventsResponse;
import com.example.file.api.dto.FileInfo;
import com.example.file.api.dto.PresignUploadRequest;
import com.example.file.api.dto.PresignUploadResponse;
import com.example.file.api.dto.StatusResponse;
import com.example.file.event.FileEventRecorder;
import com.example.file.service.FileRegistry;
import com.example.file.service.FileService;
import com.example.file.storage.StoredFile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 파일 요청 수신. REST 규약.
 *
 *   POST   /api/files            업로드 (multipart, 항목 이름 file)   → 201 + Location
 *   GET    /api/files            목록
 *   GET    /api/files/{id}       정보
 *   GET    /api/files/{id}/content   앱을 거쳐 내려받음
 *   GET    /api/files/{id}/url   저장소에서 직접 받는 임시 URL (S3 만)
 *   POST   /api/files/presign    직접 업로드 1단계 : PUT 용 임시 URL (S3 만)       → 201
 *   POST   /api/files/{id}/complete  직접 업로드 2단계 : 객체 확인 후 목록 등록  → 200
 *   DELETE /api/files/{id}       삭제                               → 204
 *   GET    /api/files/{id}/events  이벤트 이력 · 다운로드 횟수 (prod 는 DynamoDB)
 *   GET    /api/status           저장소 · 목록 DB · 캐시 · 이벤트 · 호스트명 · 파일 수
 *
 * 요청이 성공하면 이벤트를 한 건 남김(events.record). 기록이 실패하면 지금은 요청도 500 이 됨
 * — 실패를 어디서 잡을지는 수업의 설계 과제.
 */
@RestController
@RequestMapping("/api")
public class FileController {

    private final FileService fileService;
    private final FileRegistry registry;
    private final FileEventRecorder events;
    private final String registryKind;
    private final String cacheKind;

    public FileController(FileService fileService, FileRegistry registry, FileEventRecorder events,
                          @Value("${app.registry.kind:h2}") String registryKind,
                          @Value("${spring.cache.type:simple}") String cacheKind) {
        this.fileService = fileService;
        this.registry = registry;
        this.events = events;
        this.registryKind = registryKind;
        this.cacheKind = cacheKind;
    }

    @PostMapping(value = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<FileInfo> upload(@RequestParam("file") MultipartFile file) throws IOException {
        StoredFile stored = fileService.upload(file);
        events.record(stored.id(), "UPLOAD", hostName());
        return ResponseEntity.created(URI.create("/api/files/" + stored.id()))
                .body(FileInfo.from(stored));
    }

    /** 직접 업로드 1단계. local 저장소에서는 409 */
    @PostMapping("/files/presign")
    public ResponseEntity<PresignUploadResponse> presign(@RequestBody PresignUploadRequest request) {
        String contentType = request.contentType() == null || request.contentType().isBlank()
                ? MediaType.APPLICATION_OCTET_STREAM_VALUE : request.contentType();
        try {
            FileService.PendingUpload p = fileService.presignUpload(request.name(), contentType);
            return ResponseEntity.status(201).body(new PresignUploadResponse(p.id(), p.url(), p.expiresIn()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).build();
        }
    }

    /** 직접 업로드 2단계. 저장소에 객체가 없으면 400 */
    @PostMapping("/files/{id}/complete")
    public FileInfo complete(@PathVariable String id) throws IOException {
        StoredFile stored = fileService.completeUpload(id);
        events.record(stored.id(), "UPLOAD_DIRECT", hostName());
        return FileInfo.from(stored);
    }

    /** 목록. 캐시(30초)를 먼저 봄 — 캐시에 있으면 DB 를 거치지 않음 */
    @GetMapping("/files")
    public List<FileInfo> list() {
        return fileService.listCached().items().stream().map(FileInfo::from).toList();
    }

    /** 이 파일의 이벤트 이력과 다운로드 횟수. prod 는 DynamoDB Query 한 번 + GetItem 한 번 */
    @GetMapping("/files/{id}/events")
    public EventsResponse events(@PathVariable String id) {
        fileService.get(id);
        return new EventsResponse(id, events.downloads(id), events.events(id));
    }

    @GetMapping("/files/{id}")
    public FileInfo info(@PathVariable String id) {
        return FileInfo.from(fileService.get(id));
    }

    /** 앱 경유 다운로드. 서버 대역폭과 메모리를 씀 → 07 교안에서 프리사인과 비교함 */
    @GetMapping("/files/{id}/content")
    public ResponseEntity<Resource> download(@PathVariable String id) throws IOException {
        StoredFile file = fileService.get(id);
        Resource body = fileService.download(id);
        events.record(id, "DOWNLOAD", hostName());

        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(file.originalName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(mediaTypeOf(file.contentType()))
                .body(body);
    }

    /** 프리사인 GET URL. 디스크 저장소(local)에서는 404 가 아니라 409 로 "지원 안 함"을 알림 */
    @GetMapping("/files/{id}/url")
    public ResponseEntity<DownloadUrlResponse> downloadUrl(@PathVariable String id) {
        return fileService.downloadUrl(id)
                .map(url -> {
                    events.record(id, "PRESIGN_GET", hostName());
                    return ResponseEntity.ok(new DownloadUrlResponse(id, url, fileService.urlTtl().toSeconds()));
                })
                .orElseGet(() -> ResponseEntity.status(409).build());
    }

    @DeleteMapping("/files/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) throws IOException {
        fileService.delete(id);
        // 삭제 뒤에는 /events 가 404(목록에 없음) — DELETE 항목은 DynamoDB 콘솔에서 fileId 로 봄
        events.record(id, "DELETE", hostName());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/status")
    public StatusResponse status() {
        return new StatusResponse(fileService.storageKind(), registryKind, cacheKind, events.kind(),
                hostName(), registry.size());
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static MediaType mediaTypeOf(String contentType) {
        try {
            return contentType == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(contentType);
        } catch (Exception e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
