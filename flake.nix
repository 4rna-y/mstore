{
  description = "mstore - Paper サーバーを監視しつつ key-value ストアを提供する常駐アプリ";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  };

  outputs = { self, nixpkgs }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" "x86_64-darwin" "aarch64-darwin" ];
      forAllSystems = f: nixpkgs.lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});
    in
    {
      devShells = forAllSystems (pkgs:
        let
          # 監視対象の Paper 26.x (Minecraft 26.1 以降) は Java 25 以上が必須。
          jdk = pkgs.jdk25;
          # Gradle 8 系は Java 25 を release ターゲットとして受け付けない。
          gradle = pkgs.gradle_9;

          # Paper の jar を取得してパスを stdout に出す。進捗は stderr へ。
          # PaperMC の v2 API は廃止済みなので v3 (fill.papermc.io) を使う。
          paper-jar = pkgs.writeShellApplication {
            name = "paper-jar";
            runtimeInputs = [ pkgs.curl pkgs.jq pkgs.coreutils ];
            text = ''
              MC_VERSION="''${1:-}"
              RUN_DIR="''${PAPER_RUN_DIR:-./run}"
              API="https://fill.papermc.io/v3/projects/paper"
              UA="mstore/dev (local test server)"

              # 未指定なら pre/rc を除いた最新バージョンを選ぶ
              if [ -z "$MC_VERSION" ]; then
                MC_VERSION="$(curl -fsSL -H "User-Agent: $UA" "$API" \
                  | jq -r '[.versions[][]] | map(select(contains("-") | not)) | .[0]')"
                echo "==> latest stable version: $MC_VERSION" >&2
              fi

              BUILD="$(curl -fsSL -H "User-Agent: $UA" "$API/versions/$MC_VERSION/builds" \
                | jq -r '[.[] | select(.channel == "STABLE")][0].downloads["server:default"]
                         | "\(.name)\t\(.url)\t\(.checksums.sha256)"')"
              if [ -z "$BUILD" ] || [ "$BUILD" = "null" ]; then
                echo "no stable build found for $MC_VERSION" >&2
                exit 1
              fi

              JAR="$(printf '%s' "$BUILD" | cut -f1)"
              URL="$(printf '%s' "$BUILD" | cut -f2)"
              SHA="$(printf '%s' "$BUILD" | cut -f3)"

              mkdir -p "$RUN_DIR"
              if [ ! -f "$RUN_DIR/$JAR" ]; then
                echo "==> downloading $JAR" >&2
                curl -fSL -H "User-Agent: $UA" -o "$RUN_DIR/$JAR.tmp" "$URL"
                echo "$SHA  $RUN_DIR/$JAR.tmp" | sha256sum -c - >&2
                mv "$RUN_DIR/$JAR.tmp" "$RUN_DIR/$JAR"
              fi

              printf '%s\n' "$RUN_DIR/$JAR"
            '';
          };

          # mstore をビルドして ./run のサーバーを監視付きで起動する。
          # リポジトリのルートで実行すること。
          mstore-dev = pkgs.writeShellApplication {
            name = "mstore-dev";
            runtimeInputs = [ jdk gradle paper-jar pkgs.coreutils ];
            text = ''
              RUN_DIR="''${PAPER_RUN_DIR:-./run}"

              JAR="$(paper-jar "$@")"

              # ローカル検証専用サーバーなので EULA は自動同意する。
              # 同意したくない場合はこの行を消して手動で eula.txt を編集すること。
              echo "eula=true" > "$RUN_DIR/eula.txt"

              echo "==> building" >&2
              gradle --console=plain -q installDist

              exec build/install/mstore/bin/mstore \
                --server-dir "$RUN_DIR" --jar "$(basename "$JAR")"
            '';
          };
        in
        {
          default = pkgs.mkShell {
            packages = [
              jdk
              gradle
              pkgs.jdt-language-server
              pkgs.curl
              pkgs.jq
              pkgs.sqlite
              paper-jar
              mstore-dev
            ];

            JAVA_HOME = "${jdk}";

            shellHook = ''
              echo "mstore dev shell"
              echo "  java          : $(java -version 2>&1 | head -n1)"
              echo "  gradle        : $(gradle --version 2>/dev/null | awk '/^Gradle/ {print $2}')"
              echo ""
              echo "  mstore-dev [mc-version]    # ビルド → ./run のサーバーを監視付きで起動"
              echo "  paper-jar [mc-version]     # jar を取得してパスを表示"
              echo "  gradle installDist         # build/install/mstore/bin/mstore を作る"
              echo ""
              echo "  KV だけ動かす: mstore --kv-only --kv-port 8080"
              echo "  DB を覗く    : sqlite3 run/mstore.db 'select * from kv;'"
            '';
          };
        });

      formatter = forAllSystems (pkgs: pkgs.nixpkgs-fmt);
    };
}
