package io.github.mstore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * Paper サーバーの代わりに {@link Supervisor} へ掴ませるスタブ。
 *
 * <p>{@link StubServerJar} が実行可能 jar に固めて渡す。Minecraft を起動せずに
 * 「マーカーを残して落ちた」「異常終了した」といった状況だけを再現するためのもの。
 * カレントディレクトリは Supervisor が server-dir にしてくれる。
 *
 * <p>起動時にマーカーがあれば消す。これは実際のサーバーで
 * {@code WorldIsAlsoHardcoreBootstrap} が予約を消費するのに対応する。
 * {@code stub.consumeMarker=false} にすると「bootstrap が壊れて消費できない」状況になる。
 *
 * <table>
 *   <caption>システムプロパティ (--java-arg で渡す)</caption>
 *   <tr><td>{@code stub.markerUntilRun}</td><td>この回数目までマーカーを書く (既定 0 = 書かない)</td></tr>
 *   <tr><td>{@code stub.failUntilRun}</td>  <td>この回数目まで 1 で終了する (既定 0 = しない)</td></tr>
 *   <tr><td>{@code stub.consumeMarker}</td> <td>起動時にマーカーを消すか (既定 true)</td></tr>
 *   <tr><td>{@code stub.marker}</td>        <td>マーカーのパス。server-dir からの相対</td></tr>
 *   <tr><td>{@code stub.restartMarkerUntilRun}</td><td>この回数目まで再起動要求を書き残す (既定 0 = 書かない)</td></tr>
 *   <tr><td>{@code stub.restartMarker}</td> <td>再起動要求のパス。server-dir からの相対</td></tr>
 *   <tr><td>{@code stub.exit}</td>          <td>通常時の終了コード (既定 0)</td></tr>
 *   <tr><td>{@code stub.sleepMs}</td>       <td>終了前に待つ時間 (既定 0)</td></tr>
 * </table>
 */
public final class StubServer {

    /** 起動されるたびに1行増える。テストはこれで再起動回数を数える。 */
    public static final String RUN_LOG = "stub-runs.txt";

    /** 起動時にマーカーが在った回を記録する。「予約が次の起動へ渡ったか」の確認に使う。 */
    public static final String MARKER_SEEN_LOG = "stub-marker-seen.txt";

    private static final String DEFAULT_MARKER = "plugins/WorldIsAlsoHardcore/pending-reset.txt";
    private static final String DEFAULT_RESTART_MARKER = "plugins/Plugman/pending-restart.txt";

    /** 起動時に再起動要求が残っていた回を記録する。「mstore が消したか」の確認に使う。 */
    public static final String RESTART_SEEN_LOG = "stub-restart-seen.txt";

    private StubServer() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        int run = append(RUN_LOG, "run");
        Path marker = Path.of(System.getProperty("stub.marker", DEFAULT_MARKER));

        // bootstrap 相当: 予約を見つけたら消費する。
        if (Files.isRegularFile(marker)) {
            append(MARKER_SEEN_LOG, String.valueOf(run));
            if (Boolean.parseBoolean(System.getProperty("stub.consumeMarker", "true"))) {
                Files.delete(marker);
            }
        }

        // リセット発火相当: 次回起動時に消してほしいものを予約する。
        if (run <= Integer.getInteger("stub.markerUntilRun", 0)) {
            Files.createDirectories(marker.toAbsolutePath().getParent());
            Files.writeString(marker, "# stub\n/nonexistent/world\n", StandardCharsets.UTF_8);
        }

        // Plugman 相当: 起動時に再起動要求が残っていたら記録する (消すのは mstore の仕事なので触らない)。
        Path restartMarker = Path.of(System.getProperty("stub.restartMarker", DEFAULT_RESTART_MARKER));
        if (Files.isRegularFile(restartMarker)) {
            append(RESTART_SEEN_LOG, String.valueOf(run));
        }
        // 更新を置いた後の再起動要求。
        if (run <= Integer.getInteger("stub.restartMarkerUntilRun", 0)) {
            Files.createDirectories(restartMarker.toAbsolutePath().getParent());
            Files.writeString(restartMarker, "# stub: plugin update\n", StandardCharsets.UTF_8);
        }

        long sleepMs = Long.getLong("stub.sleepMs", 0L);
        if (sleepMs > 0) {
            Thread.sleep(sleepMs);
        }

        int exit = run <= Integer.getInteger("stub.failUntilRun", 0)
                ? 1
                : Integer.getInteger("stub.exit", 0);
        System.out.println("[stub] run=" + run + " exit=" + exit);
        System.exit(exit);
    }

    /** 1行足して、足した後の行数を返す。 */
    private static int append(String file, String line) throws IOException {
        Path path = Path.of(file);
        Files.writeString(path, line + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return Files.readAllLines(path).size();
    }

    /** スタブが起動された回数。 */
    public static int runCount(Path serverDir) throws IOException {
        return lines(serverDir.resolve(RUN_LOG)).size();
    }

    /** 起動時にマーカーが在った回の一覧。 */
    public static List<String> markerSeenAtRuns(Path serverDir) throws IOException {
        return lines(serverDir.resolve(MARKER_SEEN_LOG));
    }

    /** 起動時に再起動要求が残っていた回の一覧。mstore が消していれば空。 */
    public static List<String> restartSeenAtRuns(Path serverDir) throws IOException {
        return lines(serverDir.resolve(RESTART_SEEN_LOG));
    }

    private static List<String> lines(Path path) throws IOException {
        return Files.isRegularFile(path) ? Files.readAllLines(path) : List.of();
    }
}
