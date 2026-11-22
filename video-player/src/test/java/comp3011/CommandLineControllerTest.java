/*
 * COMP3011 Assignment 2
 * Author: Le Bao Uyen Truong a1909745
 *
 * Student implementation for concurrent video frame processing.
 */

package comp3011;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandLineControllerTest {

        @TempDir
        Path tempDir;

        private String videoPath;

        @BeforeEach
        void setUp() throws IOException {
                // Parsing only requires an existing file.
                // No actual video decoding happens in these tests.
                videoPath = Files.createFile(
                                tempDir.resolve("test.mp4")).toString();
        }


        @Test
        void subtitleShortAndLongOptions() {
                CommandLineController shortOption = new CommandLineController(
                        new String[] { "-S", videoPath });
                CommandLineController longOption = new CommandLineController(
                        new String[] { "--subtitle-frames", videoPath });
                assertTrue(shortOption.isSubtitleRequested());
                assertTrue(longOption.isSubtitleRequested());
                assertTrue(shortOption.shouldLaunchApplication());
                assertTrue(longOption.shouldLaunchApplication());
        }

        @Test
        void testStackedEffectOptions() {
                CommandLineController cli = new CommandLineController(
                                new String[] { "-jfddn", videoPath });

                assertEquals(
                                List.of('j', 'f', 'd', 'd', 'n'),
                                cli.getEffectOptions());

                assertTrue(cli.shouldLaunchApplication());
        }

        @Test
        void testSeparateEffectOptions() {
                CommandLineController cli = new CommandLineController(
                                new String[] {
                                                "-j", "-f", "-d", "-d", "-n", videoPath
                                });

                assertEquals(
                                List.of('j', 'f', 'd', 'd', 'n'),
                                cli.getEffectOptions());
        }

        @Test
        void testMixedLongAndShortOptions() {
                CommandLineController cli = new CommandLineController(
                                new String[] {
                                                "--jitter-frames",
                                                "-f",
                                                "--dust-frames",
                                                "-dn",
                                                videoPath
                                });

                assertEquals(
                                List.of('j', 'f', 'd', 'd', 'n'),
                                cli.getEffectOptions());
        }

        @Test
        void testAllEffectOptions() {
                CommandLineController cli = new CommandLineController(
                                new String[] { "-nsfwyvdjmbp", videoPath });

                assertEquals(
                                List.of('n', 's', 'f', 'w', 'y', 'v',
                                                'd', 'j', 'm', 'b', 'p'),
                                cli.getEffectOptions());
        }

        @Test
        void testRepeatedEffects() {
                CommandLineController cli = new CommandLineController(
                                new String[] { "-ddd", videoPath });

                assertEquals(
                                List.of('d', 'd', 'd'),
                                cli.getEffectOptions());
        }

        @Test
        void testUnknownOption() {
                CommandLineController cli = new CommandLineController(
                                new String[] { "-z", videoPath });

                assertNotNull(cli.getErrorMessage());
                assertFalse(cli.shouldLaunchApplication());
                assertEquals(1, cli.getExitCode());
        }

        @Test
        void testMissingVideoFile() {
                CommandLineController cli = new CommandLineController(
                                new String[] { "-n" });

                assertEquals(
                                "No video file specified.",
                                cli.getErrorMessage());

                assertFalse(cli.shouldLaunchApplication());
        }

        @Test
        void testNonexistentVideoFile() {
                CommandLineController cli = new CommandLineController(
                                new String[] {
                                                tempDir.resolve("missing.mp4").toString()
                                });

                assertTrue(
                                cli.getErrorMessage().startsWith("File not found:"));
        }

        @Test
        void testMultipleVideoFiles() {
                CommandLineController cli = new CommandLineController(
                                new String[] { videoPath, videoPath });

                assertNotNull(cli.getErrorMessage());
                assertFalse(cli.shouldLaunchApplication());
        }

        @Test
        void testConflictingDisplays() {
                CommandLineController cli = new CommandLineController(
                                new String[] { "-1", "-2", videoPath });

                assertEquals(
                                "Only one display option can be used",
                                cli.getErrorMessage());
        }

        @Test
        void testExistingAudioAndDisplayOptions() {
                CommandLineController cli = new CommandLineController(
                                new String[] { "-a", "-x", "-1", videoPath });

                assertTrue(cli.isAudioRequested());
                assertTrue(cli.isMaximiseRequested());
                assertEquals(1, cli.getDisplayId());
                assertTrue(cli.shouldLaunchApplication());
        }

        @Test
        void testHelpWithoutVideoFile() {
                CommandLineController cli = new CommandLineController(
                                new String[] { "--help" });

                assertNull(cli.getErrorMessage());
                assertFalse(cli.shouldLaunchApplication());
                assertEquals(0, cli.getExitCode());
        }
}