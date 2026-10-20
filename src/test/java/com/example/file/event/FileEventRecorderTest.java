package com.example.file.event;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** local 프로파일에서는 LoggingFileEventRecorder 가 주입됨. DynamoDB 없이 규약만 확인함 */
@SpringBootTest
@ActiveProfiles("local")
class FileEventRecorderTest {

    @Autowired FileEventRecorder events;

    @Test
    void local_에서는_log_구현이_주입됨() {
        assertEquals("log", events.kind());
    }

    @Test
    void 기록한_순서대로_돌려주고_다른_파일과_섞이지_않음() {
        events.record("ev-1", "UPLOAD", "host-a");
        events.record("ev-1", "DOWNLOAD", "host-b");
        events.record("ev-1", "DOWNLOAD", "host-a");
        events.record("ev-2", "UPLOAD", "host-a");

        List<FileEventRecorder.FileEvent> list = events.events("ev-1");
        assertEquals(3, list.size());
        assertEquals("UPLOAD", list.get(0).type());
        assertEquals("host-b", list.get(1).host());
        assertEquals(2, events.downloads("ev-1"));
        assertTrue(events.events("none").isEmpty());
    }
}
