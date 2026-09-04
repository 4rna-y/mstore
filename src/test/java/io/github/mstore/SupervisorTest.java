package io.github.mstore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * リセットの3段階のうち「停止を検知して起動し直す」部分の検証。
 *
 * <p>Minecraft は起動しない。{@link StubServer} を jar に固めて掴ませ、
 * 「マーカーを残して落ちた」「異常終了した」に対する Supervisor の判断だけを見る。
 * 実際に Paper と組み合わせたときの動作は wiah 側の {@code :e2e} が担当する。
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class SupervisorTest {

    private static final String MARKER = "plugins/WorldIsAlsoHardcore/pending-reset.txt";
    private static final String RESTART_MARKER = "plugins/Plugman/pending-restart.txt";

    @TempDir
    Path serverDir;

    private Path stubJar;

    @BeforeEach
    void writeStubJar() throws IOException {
        stubJar = StubServerJar.writeTo(serverDir.resolve("paper-stub.jar"));
    }

    // ------------------------------------------------------------------ 再起動する

    @Test
    @DisplayName("マーカーが残っていれば再起動する")
    void restartsWhenMarkerRemains() throws Exception {
        // 1回目でマーカーを書いて停止 (= リセットによる停止)、2回目は書かずに正常終了。
        int exit = new Supervisor(options().markerUntilRun(1).build()).run();

        assertEquals(0, exit, "リセット後の正常終了なので 0 で終わるべき");
        assertEquals(2, StubServer.runCount(serverDir), "再起動していない");
    }

    @Test
    @DisplayName("マーカーが無い正常終了ならスーパーバイザも終了する")
    void stopsWhenNoMarker() throws Exception {
        int exit = new Supervisor(options().build()).run();

        assertEquals(0, exit);
        assertEquals(1, StubServer.runCount(serverDir), "stop したのに再起動している");
    }

    @Test
    @DisplayName("予約は次の起動へ渡され、そこで消費される")
    void handsTheMarkerToTheNextBoot() throws Exception {
        // 1回目でリセットを予約し、2回目の起動時にスタブ (= bootstrap 相当) が消費する。
        int exit = new Supervisor(options().markerUntilRun(1).build()).run();

        assertEquals(0, exit);
        assertEquals(List.of("2"), StubServer.markerSeenAtRuns(serverDir),
                "2回目の起動が予約を受け取っていない");
        assertFalse(Files.exists(serverDir.resolve(MARKER)), "消費されたはずの予約が残っている");
    }

    // ------------------------------------------------------------------ 再起動要求

    @Test
    @DisplayName("再起動要求が残っていれば正常終了でも起動し直し、要求は mstore が消す")
    void restartsOnRestartMarkerAndConsumesIt() throws Exception {
        // 1回目で再起動要求を書いて exit 0 (= Plugman が更新を置いて shutdown)、2回目は書かない。
        int exit = new Supervisor(options().restartMarkerUntilRun(1).build()).run();

        assertEquals(0, exit);
        assertEquals(2, StubServer.runCount(serverDir), "再起動要求で起動し直していない");
        assertEquals(List.of(), StubServer.restartSeenAtRuns(serverDir), "2回目の起動時に要求が残っている");
        assertFalse(Files.exists(serverDir.resolve(RESTART_MARKER)));
    }

    @Test
    @DisplayName("再起動要求はリセット予約に触らない")
    void restartMarkerDoesNotTouchResetMarker() throws Exception {
        int exit = new Supervisor(options().restartMarkerUntilRun(1).build()).run();

        assertEquals(0, exit);
        assertEquals(List.of(), StubServer.markerSeenAtRuns(serverDir), "リセット予約が無いのに見えている");
    }

    @Test
    @DisplayName("再起動要求の後も短命終了が続いたら中止する")
    void abortsWhenRestartRequestsLoop() throws Exception {
        // 毎回要求を書いてすぐ終わる = 更新した jar で起動できていない、など。
        int exit = new Supervisor(options().restartMarkerUntilRun(99).build()).run();

        assertEquals(1, exit, "中止したことが分かる終了コードを返すべき");
        assertEquals(2, StubServer.runCount(serverDir), "2回目で気付かず回り続けている");
    }

    @Test
    @DisplayName("--restart-marker で指定した場所を見る")
    void honoursCustomRestartMarkerPath() throws Exception {
        String custom = "state/restart-please";
        int exit = new Supervisor(options()
                .restartMarker(custom)
                .stubProperty("stub.restartMarker", custom)
                .restartMarkerUntilRun(1)
                .build()).run();

        assertEquals(0, exit);
        assertEquals(2, StubServer.runCount(serverDir), "既定の場所しか見ていない");
        assertFalse(Files.exists(serverDir.resolve(custom)));
    }

    // ------------------------------------------------------------------ 異常終了

    @Test
    @DisplayName("短命な異常終了は上限まで再試行して諦める")
    void retriesShortLivedCrashesUpToLimit() throws Exception {
        int exit = new Supervisor(options().failUntilRun(99).maxCrashRestarts(3).build()).run();

        assertEquals(1, exit, "諦めたときは子プロセスの終了コードを返す");
        assertEquals(3, StubServer.runCount(serverDir), "再試行回数が上限と合っていない");
    }

    @Test
    @DisplayName("--no-restart-on-crash なら再起動しない")
    void doesNotRestartOnCrashWhenDisabled() throws Exception {
        int exit = new Supervisor(options().failUntilRun(99).restartOnCrash(false).build()).run();

        assertEquals(1, exit);
        assertEquals(1, StubServer.runCount(serverDir), "再起動しない設定なのに起動している");
    }

    @Test
    @DisplayName("十分に動いた後の異常終了は連続クラッシュに数えない")
    void healthyUptimeResetsTheCrashCounter() throws Exception {
        // min-healthy-seconds=0 なので、どの終了も「短命」ではない。
        // 上限 1 でも数えられないので、2回クラッシュしても諦めずに動き続ける。
        int exit = new Supervisor(options()
                .failUntilRun(2)
                .maxCrashRestarts(1)
                .minHealthySeconds(0)
                .build()).run();

        assertEquals(0, exit, "3回目は正常終了なので 0 のはず");
        assertEquals(3, StubServer.runCount(serverDir), "健全な稼働の後のクラッシュで諦めている");
    }

    // ------------------------------------------------------------------ 無限ループ防止

    @Test
    @DisplayName("マーカーが消費されないまま短命終了を繰り返したら中止する")
    void abortsWhenMarkerIsNeverConsumed() throws Exception {
        // bootstrap が壊れてマーカーを消せない状況。放っておくと永久に再起動し続ける。
        int exit = new Supervisor(options()
                .markerUntilRun(99)
                .stubProperty("stub.consumeMarker", "false")
                .build()).run();

        assertEquals(1, exit, "中止したことが分かる終了コードを返すべき");
        assertEquals(2, StubServer.runCount(serverDir), "2回目で気付かず回り続けている");
        assertTrue(Files.exists(serverDir.resolve(MARKER)), "中止時はマーカーを残すべき");
    }

    // ------------------------------------------------------------------ 事前条件

    @Test
    @DisplayName("jar が無ければ起動せずに失敗する")
    void failsWhenJarIsMissing() throws Exception {
        int exit = new Supervisor(options().jar(serverDir.resolve("no-such.jar")).build()).run();

        assertEquals(1, exit);
        assertEquals(0, StubServer.runCount(serverDir), "存在しない jar で起動を試みている");
    }

    @Test
    @DisplayName("--marker で指定した場所を見る")
    void honoursCustomMarkerPath() throws Exception {
        String custom = "state/reset-please";
        int exit = new Supervisor(options()
                .marker(custom)
                .stubProperty("stub.marker", custom)
                .markerUntilRun(1)
                .build()).run();

        assertEquals(0, exit);
        assertEquals(2, StubServer.runCount(serverDir), "既定の場所しか見ていない");
    }

    // ------------------------------------------------------------------ 組み立て

    private OptionsBuilder options() {
        return new OptionsBuilder();
    }

    /** テストで変えたいところだけ差し替えるための小さな組み立て役。 */
    private final class OptionsBuilder {

        private final List<String> javaArgs = new ArrayList<>();
        private Path jar = stubJar;
        private String marker = MARKER;
        private String restartMarker = RESTART_MARKER;
        private long minHealthySeconds = 30;
        private int maxCrashRestarts = 3;
        private boolean restartOnCrash = true;

        OptionsBuilder jar(Path value) {
            this.jar = value;
            return this;
        }

        OptionsBuilder marker(String value) {
            this.marker = value;
            return this;
        }

        OptionsBuilder restartMarker(String value) {
            this.restartMarker = value;
            return this;
        }

        OptionsBuilder restartMarkerUntilRun(int value) {
            return stubProperty("stub.restartMarkerUntilRun", String.valueOf(value));
        }

        OptionsBuilder minHealthySeconds(long value) {
            this.minHealthySeconds = value;
            return this;
        }

        OptionsBuilder maxCrashRestarts(int value) {
            this.maxCrashRestarts = value;
            return this;
        }

        OptionsBuilder restartOnCrash(boolean value) {
            this.restartOnCrash = value;
            return this;
        }

        OptionsBuilder markerUntilRun(int value) {
            return stubProperty("stub.markerUntilRun", String.valueOf(value));
        }

        OptionsBuilder failUntilRun(int value) {
            return stubProperty("stub.failUntilRun", String.valueOf(value));
        }

        OptionsBuilder stubProperty(String key, String value) {
            javaArgs.add("-D" + key + "=" + value);
            return this;
        }

        Options build() {
            return new Options(
                    serverDir, jar, List.copyOf(javaArgs), serverDir.resolve(marker),
                    serverDir.resolve(restartMarker), minHealthySeconds, maxCrashRestarts,
                    /* restartDelaySeconds */ 0, restartOnCrash,
                    /* kvEnabled */ false, /* kvOnly */ false, "127.0.0.1", 0,
                    serverDir.resolve("mstore.db"), null, 1024);
        }
    }
}
