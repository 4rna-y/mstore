package io.github.mstore;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

/**
 * mstore の入口。
 *
 * <p>やることは2つ。
 * <ul>
 *   <li>Paper サーバーを子プロセスとして起動・監視する ({@link Supervisor})</li>
 *   <li>その隣で HTTP の key-value ストアを提供する ({@link KvHttpServer})</li>
 * </ul>
 *
 * <p>ストアは mstore のプロセスに属するので、サーバーがリセットで何度再起動しても中身は残る。
 */
public final class Mstore {

    private Mstore() {
    }

    public static void main(String[] args) throws Exception {
        Optional<Options> parsed;
        try {
            parsed = Options.parse(args);
        } catch (RuntimeException e) {
            System.err.println(e.getMessage());
            System.err.println();
            System.err.println(Options.USAGE);
            System.exit(2);
            return;
        }
        if (parsed.isEmpty()) {
            System.out.println(Options.USAGE);
            return;
        }
        System.exit(run(parsed.get()));
    }

    private static int run(Options options) throws InterruptedException {
        KvStore openedStore = null;
        KvHttpServer openedHttp = null;

        if (options.kvEnabled()) {
            openedStore = SqliteKvStore.open(options.kvDb());
            try {
                openedHttp = KvHttpServer.start(
                        new InetSocketAddress(options.kvBind(), options.kvPort()),
                        openedStore, options.kvToken(), options.kvMaxValueBytes());
            } catch (IOException e) {
                openedStore.close();
                Log.info("key-value ストアを開始できませんでした: " + e.getMessage());
                return 1;
            }
            Log.info("kv         : http://" + options.kvBind() + ":" + openedHttp.address().getPort()
                    + "/kv  (" + openedStore.size() + " 件, db=" + options.kvDb() + ")");
            if (options.kvToken() == null && !isLoopback(options.kvBind())) {
                Log.info("警告: 認証なしで " + options.kvBind()
                        + " に公開しています。--kv-token の指定を推奨します。");
            }
        }

        KvStore store = openedStore;
        KvHttpServer http = openedHttp;
        Runnable closeKv = () -> {
            if (http != null) {
                http.close();
            }
            if (store != null) {
                store.close();
            }
        };

        if (options.kvOnly()) {
            Log.info("--kv-only: サーバーは起動しません。停止は Ctrl-C か SIGTERM。");
            Runtime.getRuntime().addShutdownHook(new Thread(closeKv, "mstore-shutdown"));
            // 後片付けはフックに任せるので、ここからは戻らない。
            new CountDownLatch(1).await();
            return 0;
        }

        Supervisor supervisor = new Supervisor(options);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            supervisor.stopChild();
            closeKv.run();
        }, "mstore-shutdown"));
        try {
            return supervisor.run();
        } finally {
            closeKv.run();
        }
    }

    /** 子プロセスを起動するときの java。自分を起動したのと同じものを使う。 */
    static String javaExecutable() {
        return ProcessHandle.current().info().command()
                .orElseGet(() -> Path.of(System.getProperty("java.home"), "bin", "java").toString());
    }

    private static boolean isLoopback(String bind) {
        try {
            return InetAddress.getByName(bind).isLoopbackAddress();
        } catch (UnknownHostException e) {
            // 解決できないアドレスは bind の時点で失敗する。ここでは警告を出す側に倒す。
            return false;
        }
    }
}
