package io.github.mstore;

import java.util.List;
import java.util.Optional;

/**
 * key-value ストアのバックエンド。
 *
 * <p>実装を差し替えられるようインターフェースで切ってある。既定の実装は
 * {@link SqliteKvStore}。値はバイト列として扱い、文字コードの解釈は呼び出し側に任せる。
 */
public interface KvStore extends AutoCloseable {

    /** 値を取得する。キーが無ければ空。 */
    Optional<byte[]> get(String key);

    /** 値を書き込む。新規作成なら true、既存の上書きなら false。 */
    boolean put(String key, byte[] value);

    /** 値を削除する。削除できたら true、もともと無ければ false。 */
    boolean delete(String key);

    /** prefix で始まるキーを昇順で返す。prefix が空文字なら全件。 */
    List<String> keys(String prefix);

    /** 登録されているキーの数。 */
    long size();

    @Override
    void close();
}
