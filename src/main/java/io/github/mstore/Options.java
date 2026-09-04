package io.github.mstore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * コマンドライン引数。
 *
 * @param jar 起動する Paper の jar。{@code --kv-only} のときだけ null。
 * @param restartMarker Plugman などが「もう一度起動してほしい」と書き残す再起動要求ファイル。
 *                      あれば終了コードを問わず起動し直す。消すのは mstore
 * @param kvToken null なら KV の認証をしない
 */
public record Options(
        Path serverDir,
        Path jar,
        List<String> javaArgs,
        Path markerFile,
        Path restartMarker,
        long minHealthySeconds,
        int maxCrashRestarts,
        long restartDelaySeconds,
        boolean restartOnCrash,
        boolean kvEnabled,
        boolean kvOnly,
        String kvBind,
        int kvPort,
        Path kvDb,
        String kvToken,
        int kvMaxValueBytes) {

    private static final String DEFAULT_MARKER = "plugins/WorldIsAlsoHardcore/pending-reset.txt";
    private static final String DEFAULT_RESTART_MARKER = "plugins/Plugman/pending-restart.txt";
    private static final String DEFAULT_DB = "mstore.db";
    private static final String DEFAULT_BIND = "127.0.0.1";
    private static final int DEFAULT_PORT = 8080;
    private static final int DEFAULT_MAX_VALUE_BYTES = 1024 * 1024;

    public static final String USAGE = """
            mstore - Paper サーバーを子プロセスとして起動・監視しつつ、
                     HTTP で叩ける key-value ストアを提供する。

            使い方: mstore [オプション]

            サーバー監視:
              --server-dir <path>          サーバーの作業ディレクトリ (既定: ./run)
              --jar <path>                 起動する jar (既定: server-dir 内で最も新しい paper-*.jar)
              --java-arg <arg>             java に渡す引数。複数指定可 (既定: -Xms1G -Xmx2G)
              --marker <path>              リセット予約ファイル。server-dir からの相対パス
                                           (既定: %s)
              --restart-marker <path>      再起動要求ファイル。server-dir からの相対パス。
                                           あれば終了コードを問わず起動し直し、mstore が消す
                                           (既定: %s)
              --min-healthy-seconds <n>    これ未満で終了したら「短命終了」とみなす (既定: 30)
              --max-crash-restarts <n>     短命なクラッシュが連続したら諦める回数 (既定: 3)
              --restart-delay-seconds <n>  再起動までの待ち時間 (既定: 3)
              --no-restart-on-crash        クラッシュ時は再起動せず終了する

            key-value ストア:
              --kv-port <n>                待ち受けポート。0 で空きポートを自動割り当て (既定: %d)
              --kv-bind <addr>             待ち受けアドレス (既定: %s)
              --kv-db <path>               SQLite の DB ファイル。server-dir からの相対パス
                                           (既定: %s)
              --kv-token <token>           Bearer トークンを要求する。ループバック以外に
                                           bind するなら必ず指定すること
              --kv-max-value-bytes <n>     1件あたりの値の上限 (既定: %d)
              --no-kv                      key-value ストアを起動しない
              --kv-only                    key-value ストアだけを動かし、サーバーは起動しない

              -h, --help                   このヘルプを表示する

            HTTP:
              GET    /kv/<key>             値を取る            200 / 404
              PUT    /kv/<key>             値を書く            201 (新規) / 204 (上書き)
              DELETE /kv/<key>             値を消す            204 / 404
              GET    /kv?prefix=<p>        キーを1行1件で列挙  200
              GET    /health               生存確認 (認証不要) 200
            """.formatted(DEFAULT_MARKER, DEFAULT_RESTART_MARKER, DEFAULT_PORT, DEFAULT_BIND, DEFAULT_DB,
                    DEFAULT_MAX_VALUE_BYTES);

    /** 引数を解釈する。{@code --help} が指定された場合は空を返す。 */
    public static Optional<Options> parse(String[] args) {
        Path serverDir = Path.of("run");
        Path jar = null;
        List<String> javaArgs = new ArrayList<>();
        String marker = DEFAULT_MARKER;
        String restartMarker = DEFAULT_RESTART_MARKER;
        long minHealthySeconds = 30;
        int maxCrashRestarts = 3;
        long restartDelaySeconds = 3;
        boolean restartOnCrash = true;
        boolean kvEnabled = true;
        boolean kvOnly = false;
        String kvBind = DEFAULT_BIND;
        int kvPort = DEFAULT_PORT;
        String kvDb = DEFAULT_DB;
        String kvToken = null;
        int kvMaxValueBytes = DEFAULT_MAX_VALUE_BYTES;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--server-dir" -> serverDir = Path.of(require(args, ++i, "--server-dir"));
                case "--jar" -> jar = Path.of(require(args, ++i, "--jar"));
                case "--java-arg" -> javaArgs.add(require(args, ++i, "--java-arg"));
                case "--marker" -> marker = require(args, ++i, "--marker");
                case "--restart-marker" -> restartMarker = require(args, ++i, "--restart-marker");
                case "--min-healthy-seconds" ->
                        minHealthySeconds = Long.parseLong(require(args, ++i, "--min-healthy-seconds"));
                case "--max-crash-restarts" ->
                        maxCrashRestarts = Integer.parseInt(require(args, ++i, "--max-crash-restarts"));
                case "--restart-delay-seconds" ->
                        restartDelaySeconds = Long.parseLong(require(args, ++i, "--restart-delay-seconds"));
                case "--no-restart-on-crash" -> restartOnCrash = false;
                case "--kv-port" -> kvPort = Integer.parseInt(require(args, ++i, "--kv-port"));
                case "--kv-bind" -> kvBind = require(args, ++i, "--kv-bind");
                case "--kv-db" -> kvDb = require(args, ++i, "--kv-db");
                case "--kv-token" -> kvToken = require(args, ++i, "--kv-token");
                case "--kv-max-value-bytes" ->
                        kvMaxValueBytes = Integer.parseInt(require(args, ++i, "--kv-max-value-bytes"));
                case "--no-kv" -> kvEnabled = false;
                case "--kv-only" -> kvOnly = true;
                case "-h", "--help" -> {
                    return Optional.empty();
                }
                default -> throw new IllegalArgumentException("不明な引数: " + args[i]);
            }
        }

        if (kvOnly && !kvEnabled) {
            throw new IllegalArgumentException("--kv-only と --no-kv は同時に指定できません");
        }
        if (kvPort < 0 || kvPort > 65535) {
            throw new IllegalArgumentException("--kv-port は 0-65535 の範囲で指定してください");
        }
        if (kvMaxValueBytes <= 0) {
            throw new IllegalArgumentException("--kv-max-value-bytes は 1 以上で指定してください");
        }

        if (javaArgs.isEmpty()) {
            javaArgs = List.of("-Xms1G", "-Xmx2G");
        }
        serverDir = serverDir.toAbsolutePath().normalize();

        // --kv-only ではサーバーを起動しないので jar を探しにいかない。
        // (探すと jar が無いだけで起動に失敗してしまう)
        if (!kvOnly) {
            if (jar == null) {
                jar = findNewestPaperJar(serverDir);
            }
            jar = serverDir.resolve(jar).toAbsolutePath().normalize();
        } else {
            jar = null;
        }

        return Optional.of(new Options(serverDir, jar, List.copyOf(javaArgs),
                serverDir.resolve(marker).normalize(), serverDir.resolve(restartMarker).normalize(),
                minHealthySeconds, maxCrashRestarts,
                restartDelaySeconds, restartOnCrash, kvEnabled, kvOnly, kvBind, kvPort,
                serverDir.resolve(kvDb).normalize(), kvToken, kvMaxValueBytes));
    }

    private static String require(String[] args, int index, String flag) {
        if (index >= args.length) {
            throw new IllegalArgumentException(flag + " には値が必要です");
        }
        return args[index];
    }

    /** server-dir 直下で最も更新が新しい paper-*.jar を選ぶ。 */
    private static Path findNewestPaperJar(Path serverDir) {
        try (Stream<Path> entries = Files.list(serverDir)) {
            return entries
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith("paper-") && name.endsWith(".jar");
                    })
                    .max(Comparator.comparingLong(p -> p.toFile().lastModified()))
                    .orElseThrow(() -> new IllegalStateException(
                            serverDir + " に paper-*.jar が見つかりません。--jar で指定してください。"));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(serverDir + " を読めません: " + e.getMessage(), e);
        }
    }
}
