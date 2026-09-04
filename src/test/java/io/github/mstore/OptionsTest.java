package io.github.mstore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** コマンドライン引数の解釈。 */
class OptionsTest {

    @TempDir
    Path serverDir;

    @Test
    @DisplayName("--help は空を返す")
    void helpReturnsEmpty() {
        assertTrue(Options.parse(new String[] {"--help"}).isEmpty());
        assertTrue(Options.parse(new String[] {"-h"}).isEmpty());
    }

    @Test
    @DisplayName("marker と kv-db は server-dir からの相対で解決する")
    void resolvesPathsAgainstServerDir() throws IOException {
        givenPaperJar("paper-1.jar");

        Options options = parse("--server-dir", serverDir.toString());

        assertEquals(serverDir.resolve("plugins/WorldIsAlsoHardcore/pending-reset.txt"),
                options.markerFile());
        assertEquals(serverDir.resolve("plugins/Plugman/pending-restart.txt"),
                options.restartMarker());
        assertEquals(serverDir.resolve("mstore.db"), options.kvDb());
        assertEquals(serverDir.toAbsolutePath().normalize(), options.serverDir());
    }

    @Test
    @DisplayName("--restart-marker も server-dir からの相対で解決する")
    void resolvesRestartMarkerAgainstServerDir() throws IOException {
        givenPaperJar("paper-1.jar");

        Options options = parse("--server-dir", serverDir.toString(),
                "--restart-marker", "state/restart");

        assertEquals(serverDir.resolve("state/restart"), options.restartMarker());
    }

    @Test
    @DisplayName("jar 未指定なら server-dir で最も新しい paper-*.jar を選ぶ")
    void picksNewestPaperJar() throws IOException {
        givenPaperJar("paper-old.jar", Instant.now().minusSeconds(3600));
        Path newest = givenPaperJar("paper-new.jar", Instant.now());
        givenPaperJar("not-paper.jar", Instant.now().plusSeconds(3600));

        Options options = parse("--server-dir", serverDir.toString());

        assertEquals(newest.toAbsolutePath().normalize(), options.jar());
    }

    @Test
    @DisplayName("paper-*.jar が無ければ理由が分かる形で失敗する")
    void failsWhenNoPaperJar() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> parse("--server-dir", serverDir.toString()));
        assertTrue(e.getMessage().contains("paper-*.jar"), e.getMessage());
    }

    @Test
    @DisplayName("--kv-only なら jar を探さない")
    void kvOnlySkipsJarLookup() {
        // paper-*.jar が1つも無いディレクトリでも起動できる。
        Options options = parse("--server-dir", serverDir.toString(), "--kv-only");

        assertTrue(options.kvOnly());
        assertNull(options.jar(), "--kv-only なのに jar を解決している");
    }

    @Test
    @DisplayName("--no-kv で KV を止められる")
    void noKvDisablesTheStore() throws IOException {
        givenPaperJar("paper-1.jar");

        assertFalse(parse("--server-dir", serverDir.toString(), "--no-kv").kvEnabled());
        assertTrue(parse("--server-dir", serverDir.toString()).kvEnabled(), "既定では有効なはず");
    }

    @Test
    @DisplayName("--java-arg は指定順に積み上がる")
    void javaArgsAccumulate() throws IOException {
        givenPaperJar("paper-1.jar");

        Options options = parse("--server-dir", serverDir.toString(),
                "--java-arg", "-Xmx4G", "--java-arg", "-XX:+UseZGC");

        assertEquals(java.util.List.of("-Xmx4G", "-XX:+UseZGC"), options.javaArgs());
    }

    @Test
    @DisplayName("矛盾した指定は理由付きで弾く")
    void rejectsContradictoryOptions() {
        assertMessageContains(() -> parse("--kv-only", "--no-kv"), "同時に指定できません");
        assertMessageContains(() -> parse("--kv-only", "--kv-port", "99999"), "0-65535");
        assertMessageContains(() -> parse("--kv-only", "--kv-max-value-bytes", "0"), "1 以上");
        assertMessageContains(() -> parse("--bogus"), "不明な引数");
        assertMessageContains(() -> parse("--server-dir"), "値が必要");
    }

    // ------------------------------------------------------------------ 補助

    private static Options parse(String... args) {
        Optional<Options> parsed = Options.parse(args);
        return parsed.orElseThrow(() -> new AssertionError("--help でないのに空が返った"));
    }

    private static void assertMessageContains(Runnable action, String fragment) {
        RuntimeException e = assertThrows(RuntimeException.class, action::run);
        assertTrue(e.getMessage().contains(fragment),
                "メッセージに \"" + fragment + "\" が含まれない: " + e.getMessage());
    }

    private Path givenPaperJar(String name) throws IOException {
        return givenPaperJar(name, Instant.now());
    }

    private Path givenPaperJar(String name, Instant modified) throws IOException {
        Path jar = Files.writeString(serverDir.resolve(name), "");
        Files.setLastModifiedTime(jar, FileTime.from(modified));
        return jar;
    }
}
