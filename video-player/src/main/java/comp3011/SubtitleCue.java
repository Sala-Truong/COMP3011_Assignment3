package comp3011;

/** One phrase of text displayed between two media timestamps, in seconds. */
public record SubtitleCue(double start, double end, String text) {
    public boolean visibleAt(double timeSeconds) {
        return timeSeconds >= start && timeSeconds < end;
    }
}
