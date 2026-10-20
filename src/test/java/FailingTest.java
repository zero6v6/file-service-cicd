import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class FailingTest {
    @Test
    void 일부러_실패() {
        assertEquals(1, 2);
    }
}
