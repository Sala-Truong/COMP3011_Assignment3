package comp3011;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SubtitleFormatterTest {
    @Test
    void shortPhraseIsOneCard() {
        var cues = SubtitleFormatter.format(1, 4, "Hello everyone.");
        assertEquals(1, cues.size());
        assertEquals("Hello everyone.", cues.getFirst().text());
        assertTrue(cues.getFirst().visibleAt(2));
        assertFalse(cues.getFirst().visibleAt(5));
    }

    @Test
    void longPhraseWrapsToAtMostTwoLines() {
        String words = "Good morning everyone and welcome to this demonstration of the cloud and concurrent subtitle feature in our custom video player application.";
        var cues = SubtitleFormatter.format(2, 13, words);
        assertTrue(cues.size() >= 2);
        for (var cue : cues) {
            String[] lines = cue.text().split("\\n");
            assertTrue(lines.length <= 2);
            for (String line : lines) assertTrue(line.length() <= 42, line);
            assertTrue(cue.end() - cue.start() >= 1);
            assertTrue(cue.end() - cue.start() <= 7);
        }
    }

    @Test
    void blankHasNoSubtitle() {
        assertTrue(SubtitleFormatter.format(0, 1, "  ").isEmpty());
    }
}
