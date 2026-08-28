# mstore

Minecraft の Paper サーバーの隣で動く常駐アプリ。役割は2つある。

1. **サーバーの監視** — Paper を子プロセスとして起動し、落ちたら状況に応じて起動し直す。
2. **key-value ストア** — HTTP で読み書きできる単純な KV ストア。バックエンドは SQLite。

ストアは mstore のプロセスに属するので、監視対象のサーバーが何度再起動しても中身は残る。
[`wiah`](https://github.com/4rna-y/wiah) のようにワールドを消して作り直す
プラグインでも、ワールドをまたいで残したい値をここに置ける。

## サーバーの監視

`WorldIsAlsoHardcore` プラグインはワールドをリセットするためにサーバーを停止する。停止した
サーバーを起動し直すのはプラグインの担当外なので、その役目を mstore が担う。

「リセットによる停止」と「管理者による意図的な停止」は、プラグインが書き出す予約ファイル
(既定: `plugins/WorldIsAlsoHardcore/pending-reset.txt`) の有無で区別する。

- 予約ファイルが**ある** → 次回起動時に削除されるワールドがあるということなので、再起動する。
- 予約ファイルが**ない**正常終了 (`stop` コマンド等) → mstore 自身も終了する。
- 異常終了 → `--max-crash-restarts` の範囲で再起動する。

予約ファイルが消費されないまま短命終了を繰り返した場合は、無限ループを避けて中止する。

標準入出力は子プロセスへそのまま引き継ぐので、コンソールコマンドは通常どおり使える。
SIGTERM / Ctrl-C を受けるとサーバーへ停止を要求し、保存を待ってから終了する。

## key-value ストア

```console
$ curl -X PUT localhost:8080/kv/deaths -d 3
$ curl localhost:8080/kv/deaths
3
$ curl -X DELETE localhost:8080/kv/deaths
```

| メソッド | パス | 意味 | ステータス |
| --- | --- | --- | --- |
| `GET` | `/kv/<key>` | 値を取る | `200` / `404` |
| `PUT` | `/kv/<key>` | 値を書く (本文が値) | `201` 新規 / `204` 上書き |
| `DELETE` | `/kv/<key>` | 値を消す | `204` / `404` |
| `GET` | `/kv?prefix=<p>` | キーを1行1件で列挙 | `200` |
| `GET` | `/health` | 生存確認 (認証不要) | `200` |

- キーはパスの `/kv/` 以降すべて。`/` を含めてよいので `world/overworld/seed` のような
  階層的なキーがそのまま使える。非 ASCII のキーはパーセントエンコードする。
- 値はバイト列として保存し、`application/octet-stream` で返す。中身の解釈はしない。
  1件あたりの上限は `--kv-max-value-bytes` (既定 1 MiB)。超えると `413`。
- 認証は既定では無い。代わりにループバック (`127.0.0.1`) だけで待ち受ける。
  外に出すなら `--kv-bind` と併せて `--kv-token` を必ず指定する
  (`Authorization: Bearer <token>` を要求するようになる)。

### バックエンド

SQLite のファイル1つ (既定 `<server-dir>/mstore.db`)。スキーマは1テーブルだけ。

```sql
CREATE TABLE kv (
  key        TEXT PRIMARY KEY,
  value      BLOB NOT NULL,
  updated_at INTEGER NOT NULL   -- epoch ミリ秒
);
```

`sqlite3 run/mstore.db 'select key, cast(value as text) from kv;'` でそのまま覗ける。
別の DB に差し替えたい場合は `KvStore` インターフェースを実装して
`Mstore#run` の `SqliteKvStore.open(...)` を置き換える。

## 使い方

```console
$ mstore --server-dir /srv/minecraft --java-arg -Xmx4G
$ mstore --kv-only --kv-port 8080              # KV ストアだけ動かす
```

### サーバー監視

| オプション | 既定値 | 説明 |
| --- | --- | --- |
| `--server-dir <path>` | `./run` | サーバーの作業ディレクトリ |
| `--jar <path>` | 自動検出 | 起動する jar。省略時は server-dir 内で最も新しい `paper-*.jar` |
| `--java-arg <arg>` | `-Xms1G -Xmx2G` | java に渡す引数。複数指定可 |
| `--marker <path>` | `plugins/WorldIsAlsoHardcore/pending-reset.txt` | リセット予約ファイル。server-dir からの相対パス |
| `--min-healthy-seconds <n>` | `30` | これ未満で終了したら「短命終了」とみなす |
| `--max-crash-restarts <n>` | `3` | 短命なクラッシュが連続したら諦める回数 |
| `--restart-delay-seconds <n>` | `3` | 再起動までの待ち時間 |
| `--no-restart-on-crash` | — | クラッシュ時は再起動せず終了する |

### key-value ストア

| オプション | 既定値 | 説明 |
| --- | --- | --- |
| `--kv-port <n>` | `8080` | 待ち受けポート。`0` で空きポートを自動割り当て |
| `--kv-bind <addr>` | `127.0.0.1` | 待ち受けアドレス |
| `--kv-db <path>` | `mstore.db` | SQLite の DB ファイル。server-dir からの相対パス |
| `--kv-token <token>` | — | `Authorization: Bearer <token>` を要求する |
| `--kv-max-value-bytes <n>` | `1048576` | 1件あたりの値の上限 |
| `--no-kv` | — | key-value ストアを起動しない |
| `--kv-only` | — | key-value ストアだけを動かし、サーバーは起動しない |

systemd で動かす例:

```ini
[Service]
WorkingDirectory=/srv/minecraft
ExecStart=/opt/mstore/bin/mstore --server-dir /srv/minecraft --java-arg -Xmx4G
Restart=on-failure
KillSignal=SIGTERM
TimeoutStopSec=120
```

## ビルド

```console
$ nix develop
$ gradle installDist        # build/install/mstore/bin/mstore
```

`nix develop` に JDK 25 / Gradle 9 / sqlite CLI と、ローカル検証用の補助コマンドを用意してある。

```console
$ mstore-dev               # ビルド → ./run のサーバーを監視付きで起動
$ paper-jar 1.21.4         # jar を取得してパスだけ表示
```

`paper-jar` は PaperMC の v3 API から最新の安定ビルドを取得し、SHA256 を検証する。
バージョンを固定したい場合は引数で指定する (`mstore-dev 1.21.4` のように `mstore-dev` にも渡せる)。

プラグインと合わせて動かす場合は [`wiah`](https://github.com/4rna-y/wiah) 側の `wiah-dev` を使う。
そちらがプラグインをビルドして `./run/plugins` へ配置し、mstore 経由でサーバーを起動する。
