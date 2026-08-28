package io.github.mstore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * SQLite をバックエンドにした {@link KvStore}。
 *
 * <p>接続は1本だけ持ち、すべての操作を同期化する。HTTP 側はリクエストごとにスレッドが立つが、
 * この用途で捌く量なら直列化のコストより実装の単純さを取るほうが得。
 */
public final class SqliteKvStore implements KvStore {

    private final Connection connection;
    private final Path dbFile;

    private SqliteKvStore(Connection connection, Path dbFile) {
        this.connection = connection;
        this.dbFile = dbFile;
    }

    /** DB ファイルを開く (無ければ作る)。 */
    public static SqliteKvStore open(Path dbFile) {
        Path absolute = dbFile.toAbsolutePath().normalize();
        try {
            Path parent = absolute.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            throw new IllegalStateException(absolute.getParent() + " を作れません: " + e.getMessage(), e);
        }

        try {
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + absolute);
            try (Statement statement = connection.createStatement()) {
                // WAL にしておくと読み書きが競合しにくく、プロセスが落ちても壊れにくい。
                statement.execute("PRAGMA journal_mode = WAL");
                statement.execute("PRAGMA synchronous = NORMAL");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS kv (
                          key        TEXT PRIMARY KEY,
                          value      BLOB NOT NULL,
                          updated_at INTEGER NOT NULL
                        )
                        """);
            } catch (SQLException e) {
                connection.close();
                throw e;
            }
            return new SqliteKvStore(connection, absolute);
        } catch (SQLException e) {
            throw new IllegalStateException(absolute + " を開けません: " + e.getMessage(), e);
        }
    }

    /** 開いている DB ファイルのパス。 */
    public Path dbFile() {
        return dbFile;
    }

    @Override
    public synchronized Optional<byte[]> get(String key) {
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT value FROM kv WHERE key = ?")) {
            statement.setString(1, key);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(rows.getBytes(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("get に失敗しました: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized boolean put(String key, byte[] value) {
        boolean created = get(key).isEmpty();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO kv (key, value, updated_at) VALUES (?, ?, ?)
                ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at
                """)) {
            statement.setString(1, key);
            statement.setBytes(2, value);
            statement.setLong(3, System.currentTimeMillis());
            statement.executeUpdate();
            return created;
        } catch (SQLException e) {
            throw new IllegalStateException("put に失敗しました: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized boolean delete(String key) {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM kv WHERE key = ?")) {
            statement.setString(1, key);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("delete に失敗しました: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized List<String> keys(String prefix) {
        String sql = prefix.isEmpty()
                ? "SELECT key FROM kv ORDER BY key"
                : "SELECT key FROM kv WHERE key LIKE ? ESCAPE '\\' ORDER BY key";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (!prefix.isEmpty()) {
                statement.setString(1, escapeLike(prefix) + "%");
            }
            try (ResultSet rows = statement.executeQuery()) {
                List<String> keys = new ArrayList<>();
                while (rows.next()) {
                    String key = rows.getString(1);
                    // SQLite の LIKE は ASCII の大小を区別しない。索引を使わせるための
                    // 絞り込みとして LIKE を掛けたうえで、一致判定は Java 側でやり直す。
                    if (key.startsWith(prefix)) {
                        keys.add(key);
                    }
                }
                return keys;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("keys に失敗しました: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized long size() {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM kv")) {
            return rows.next() ? rows.getLong(1) : 0;
        } catch (SQLException e) {
            throw new IllegalStateException("size に失敗しました: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            Log.info("DB を閉じられませんでした: " + e.getMessage());
        }
    }

    /** LIKE のワイルドカードを打ち消す。ESCAPE '\' と対で使う。 */
    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
