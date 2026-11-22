/*
 * COMP3011 Assignment 2
 * Author: Le Bao Uyen Truong a1909745
 *
 * Student implementation for concurrent video frame processing.
 */

package comp3011;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class FrameProcessorFactory {

    private FrameProcessorFactory() {
        // Utility class.
    }

    /**
     * Creates a new, independent processor pipeline.
     * Each repeated effect receives a stable identity within its type.
     */
    public static List<FrameProcessor> createProcessors(
            List<Character> effectOptions) {

        List<FrameProcessor> processors = new ArrayList<>();
        Map<Character, Integer> counts = new HashMap<>();

        for (char option : effectOptions) {
            int index = counts.getOrDefault(option, 0);
            counts.put(option, index + 1);

            FrameProcessor processor = switch (option) {
                case 'n' -> new FrameNumberer();
                case 's' -> new FrameScratcher(index);
                case 'f' -> new FrameFlickerer(index);
                case 'w' -> new FrameBlackAndWhiter();
                case 'y' -> new FrameYellower();
                case 'v' -> new FrameVignetter();
                case 'd' -> new FrameDuster(index);
                case 'j' -> new FrameJitterer(index);
                case 'm' -> new FrameMottler(index);
                case 'b' -> new FrameBleeder();
                case 'p' -> new FramePepperer(index);

                default -> throw new IllegalArgumentException(
                        "Unknown frame processor option: " + option);
            };

            processors.add(processor);
        }

        return processors;
    }
}