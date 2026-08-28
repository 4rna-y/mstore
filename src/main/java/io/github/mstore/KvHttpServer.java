package io.github.mstore;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;

/**
 * {@link KvStore} を HTTP で公開する。
 *
 * <pre>
 *   GET    /kv/&lt;key&gt;          値を取る            200 / 404
 *   PUT    /kv/&lt;key&gt;          値を書く            201 (新規) / 204 (上書き)
 *   DELETE /kv/&lt;key&gt;          値を消す            204 / 404
 *   GET    /kv?prefix=&lt;p&gt;     キーを1行1件で列挙  200
 *   GET    /health            生存確認            200
 * </pre>
 *
 * <p>キーはパスの {@code /kv/} 以降すべて。{@code /} を含めても構わないので
 * {@code /kv/world/deaths} のような階層的なキーがそのまま使える。
 */
public final class KvHttpServer implements AutoCloseable {

    private static final String KV_PREFIX = "/kv/";

    private final HttpServer server;
    private final KvStore store;
    private final byte[] token;
    private final int maxValueBytes;

    private KvHttpServer(HttpServer server, KvStore store, String token, int maxValueBytes) {
        this.server = server;
        this.store = store;
        this.token = token == null ? null : token.getBytes(StandardCharsets.UTF_8);
        this.maxValueBytes = maxValueBytes;
    }

    /**
     * 待ち受けを開始する。
     *
     * @param token null なら認証しない。ループバック以外に bind するときは指定すること。
     */
    public static KvHttpServer start(InetSocketAddress address, KvStore store, String token,
            int maxValueBytes) throws IOException {
        HttpServer http = HttpServer.create(address, 0);
        KvHttpServer kv = new KvHttpServer(http, store, token, maxValueBytes);
        http.createContext("/", kv::dispatch);
        // リクエストごとに仮想スレッド。待ちの大半は SQLite の同期化待ちなので十分。
        http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        http.start();
        return kv;
    }

    /** 実際に割り当てられた待ち受けアドレス。ポートに 0 を指定した場合の確認に使う。 */
    public InetSocketAddress address() {
        return server.getAddress();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void dispatch(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getRawPath();

            if (path.equals("/health")) {
                // 監視から叩けるよう認証の対象外にする。中身は何も漏らさない。
                respond(exchange, 200, "ok\n");
                return;
            }
            if (!authorized(exchange)) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                respond(exchange, 401, "unauthorized\n");
                return;
            }

            if (path.equals("/kv") || path.equals("/kv/")) {
                handleList(exchange);
            } else if (path.startsWith(KV_PREFIX)) {
                handleKey(exchange, decode(path.substring(KV_PREFIX.length())));
            } else {
                respond(exchange, 404, "no such endpoint\n");
            }
        } catch (RuntimeException e) {
            // ここで潰さないとハンドラの例外が握り潰されて接続だけ切れる。
            Log.info("リクエストの処理に失敗しました: " + e);
            try {
                respond(exchange, 500, "internal error\n");
            } catch (IOException ignored) {
                // 応答ヘッダを送った後で失敗した場合。接続を閉じる以上のことはできない。
            }
        }
    }

    private void handleList(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) {
            methodNotAllowed(exchange, "GET");
            return;
        }
        List<String> keys = store.keys(queryParam(exchange, "prefix").orElse(""));
        respond(exchange, 200, keys.isEmpty() ? "" : String.join("\n", keys) + "\n");
    }

    private void handleKey(HttpExchange exchange, String key) throws IOException {
        switch (exchange.getRequestMethod()) {
            case "GET" -> {
                Optional<byte[]> value = store.get(key);
                if (value.isEmpty()) {
                    respond(exchange, 404, "no such key\n");
                } else {
                    exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
                    exchange.sendResponseHeaders(200, value.get().length);
                    try (OutputStream body = exchange.getResponseBody()) {
                        body.write(value.get());
                    }
                }
            }
            case "PUT" -> {
                // 上限 +1 バイト読んで、超えていれば受け付けない。
                byte[] value = exchange.getRequestBody().readNBytes(maxValueBytes + 1);
                if (value.length > maxValueBytes) {
                    respond(exchange, 413, "value larger than " + maxValueBytes + " bytes\n");
                    return;
                }
                boolean created = store.put(key, value);
                if (created) {
                    exchange.getResponseHeaders().set("Location", KV_PREFIX + key);
                }
                exchange.sendResponseHeaders(created ? 201 : 204, -1);
            }
            case "DELETE" -> {
                if (store.delete(key)) {
                    exchange.sendResponseHeaders(204, -1);
                } else {
                    respond(exchange, 404, "no such key\n");
                }
            }
            default -> methodNotAllowed(exchange, "GET, PUT, DELETE");
        }
    }

    private boolean authorized(HttpExchange exchange) {
        if (token == null) {
            return true;
        }
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return false;
        }
        byte[] presented = header.substring("Bearer ".length()).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(token, presented);
    }

    private static void methodNotAllowed(HttpExchange exchange, String allowed) throws IOException {
        exchange.getResponseHeaders().set("Allow", allowed);
        respond(exchange, 405, "method not allowed\n");
    }

    private static void respond(HttpExchange exchange, int status, String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }

    private static Optional<String> queryParam(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) {
            return Optional.empty();
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String rawKey = eq < 0 ? pair : pair.substring(0, eq);
            if (decode(rawKey).equals(name)) {
                return Optional.of(eq < 0 ? "" : decode(pair.substring(eq + 1)));
            }
        }
        return Optional.empty();
    }

    private static String decode(String raw) {
        // URLDecoder はフォーム用なので '+' を空白に変えてしまう。
        // パスとクエリ値では '+' はそのままの文字なので、先に退避しておく。
        return URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8);
    }
}
