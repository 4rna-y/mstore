package io.github.mstore;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Paper サーバーを子プロセスとして起動し続ける。
 *
 * <p>WorldIsAlsoHardcore はワールドをリセットするためにサーバーを停止する。停止したサーバーを
 * 起動し直すのはプラグインの担当外なので、その役目をここが担う。
 *
 * <p>「リセットによる停止」と「管理者による意図的な停止」は、プラグインが書き出す予約ファイル
 * (pending-reset.txt) の有無で区別する。予約ファイルが残っていれば次回起動時に削除される
 * ワールドがあるということなので、再起動する。
 *
 * <p>KV ストアは mstore 側のプロセスに属するので、ここで子プロセスを何度作り直しても中身は残る。
 */
public final class Supervisor {

    private final Options options;

    private volatile Process current;
    private volatile boolean stopping;

    public Supervisor(Options options) {
        this.options = options;
    }

    /** サーバーを監視し続ける。戻り値はそのまま mstore の終了コードになる。 */
    public int run() throws InterruptedException {
        if (!Files.isRegularFile(options.jar())) {
            Log.info("jar が見つかりません: " + options.jar());
            return 1;
        }

        Log.info("server-dir : " + options.serverDir());
        Log.info("jar        : " + options.jar());
        Log.info("marker     : " + options.markerFile());

        int consecutiveCrashes = 0;
        boolean previousExitHadMarker = false;

        while (true) {
            long startedAt = System.nanoTime();
            int exitCode;
            try {
                exitCode = start();
            } catch (IOException e) {
                Log.info("サーバーを起動できませんでした: " + e.getMessage());
                return 1;
            }

            if (stopping) {
                Log.info("停止要求を受け取ったので mstore を終了します。");
                return 0;
            }

            Duration uptime = Duration.ofNanos(System.nanoTime() - startedAt);
            boolean shortLived = uptime.toSeconds() < options.minHealthySeconds();
            boolean resetPending = Files.isRegularFile(options.markerFile());

            Log.info("サーバーが終了しました (exit=" + exitCode + ", 稼働 " + uptime.toSeconds() + "s)");

            if (resetPending) {
                // 予約が消費されないまま短命終了を繰り返す = 起動できていない。
                if (shortLived && previousExitHadMarker) {
                    Log.info("リセット予約が消費されないまま再起動を繰り返しています。中止します。");
                    return 1;
                }
                previousExitHadMarker = true;
                consecutiveCrashes = 0;
                Log.info("リセット予約を検出しました。ワールドを再生成するため再起動します。");
                sleep(options.restartDelaySeconds());
                continue;
            }

            previousExitHadMarker = false;

            if (exitCode == 0) {
                Log.info("リセット予約がない通常終了のため、mstore も終了します。");
                return 0;
            }

            if (!options.restartOnCrash()) {
                Log.info("--no-restart-on-crash が指定されているため終了します。");
                return exitCode;
            }

            consecutiveCrashes = shortLived ? consecutiveCrashes + 1 : 0;
            if (consecutiveCrashes >= options.maxCrashRestarts()) {
                Log.info("短時間での異常終了が " + consecutiveCrashes + " 回続きました。再起動を中止します。");
                return exitCode;
            }
            Log.info("異常終了のため再起動します (" + consecutiveCrashes + "/" + options.maxCrashRestarts() + ")");
            sleep(options.restartDelaySeconds());
        }
    }

    /** サーバーを起動し、終了するまで待つ。標準入出力は引き継ぐのでコンソール操作がそのまま使える。 */
    private int start() throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(Mstore.javaExecutable());
        command.addAll(options.javaArgs());
        command.add("-jar");
        command.add(options.jar().toString());
        command.add("nogui");

        Log.info("起動: " + String.join(" ", command));
        Process process = new ProcessBuilder(command)
                .directory(options.serverDir().toFile())
                .inheritIO()
                .start();
        current = process;
        try {
            return process.waitFor();
        } finally {
            current = null;
        }
    }

    /** SIGTERM / Ctrl-C を受けたときに子プロセスも確実に止める。 */
    public void stopChild() {
        stopping = true;
        Process process = current;
        if (process == null || !process.isAlive()) {
            return;
        }
        Log.info("サーバーへ停止を要求します。");
        process.destroy();
        try {
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                Log.info("時間内に停止しなかったので強制終了します。");
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private static void sleep(long seconds) throws InterruptedException {
        if (seconds > 0) {
            TimeUnit.SECONDS.sleep(seconds);
        }
    }
}
