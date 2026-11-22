package comp3011;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts a transcript segment into small, readable subtitle cards.
 * At most 42 characters per line and two lines per card.
 *
 * AI assistance: ChatGPT suggested the initial greedy wrapping algorithm.
 */
public final class SubtitleFormatter {
    private SubtitleFormatter() { }

    public static List<SubtitleCue> format(double start, double end, String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> cards = new ArrayList<>();
        String[] words = raw.strip().split("\\s+");
        StringBuilder line = new StringBuilder();
        List<String> lines = new ArrayList<>(2);

        for (String word : words) {
            // Split extremely long words instead of making a line wider than 42.
            while (word.length() > 42) {
                if (!line.isEmpty()) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                if (lines.size() == 2) {
                    cards.add(String.join("\n", lines));
                    lines.clear();
                }
                lines.add(word.substring(0, 42));
                word = word.substring(42);
            }
            if (word.isEmpty()) continue;
            if (line.length() + (line.isEmpty() ? 0 : 1) + word.length() > 42) {
                lines.add(line.toString());
                line.setLength(0);
                if (lines.size() == 2) {
                    cards.add(String.join("\n", lines));
                    lines.clear();
                }
            }
            if (!line.isEmpty()) line.append(' ');
            line.append(word);
        }
        if (!line.isEmpty()) lines.add(line.toString());
        if (!lines.isEmpty()) cards.add(String.join("\n", lines));

        double span = Math.max(0, end - start);
        double each = cards.isEmpty() ? 1 : Math.max(1.0, span / cards.size());
        List<SubtitleCue> cues = new ArrayList<>();
        for (int i = 0; i < cards.size(); i++) {
            double cueStart = start + (span * i / cards.size());
            // Display each card for between 1 and 7 seconds where possible.
            double cueEnd = cueStart + Math.min(7, each);
            cues.add(new SubtitleCue(cueStart, cueEnd, cards.get(i)));
        }
        return List.copyOf(cues);
    }
}
