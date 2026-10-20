package com.example.file.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** 내 PC 용 이벤트 기록. DynamoDB 없이 메모리와 로그로만 · 앱을 끄면 사라짐 */
@Component
@Profile("local")
public class LoggingFileEventRecorder implements FileEventRecorder {

    private static final Logger log = LoggerFactory.getLogger(LoggingFileEventRecorder.class);

    private final Map<String, List<FileEvent>> events = new ConcurrentHashMap<>();

    @Override
    public String kind() {
        return "log";
    }

    @Override
    public void record(String fileId, String type, String host) {
        FileEvent e = new FileEvent(fileId, Instant.now().toString(), type, host);
        events.computeIfAbsent(fileId, k -> new CopyOnWriteArrayList<>()).add(e);
        log.info("이벤트 : {} {} {}", type, fileId, host);
    }

    @Override
    public List<FileEvent> events(String fileId) {
        return List.copyOf(events.getOrDefault(fileId, List.of()));
    }

    @Override
    public long downloads(String fileId) {
        return events(fileId).stream().filter(e -> "DOWNLOAD".equals(e.type())).count();
    }
}
