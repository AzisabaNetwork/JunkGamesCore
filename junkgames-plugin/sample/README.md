# JunkGamesSample

JunkGames プラグインのひな形です。新しいプラグインはこのディレクトリをコピーして作ります。

| ファイル | 内容 |
|---|---|
| `src/main/resources/plugin.yml` | `depend: [JunkGamesCore]` とコマンド `/jgsample` の宣言 |
| `src/main/kotlin/.../SamplePlugin.kt` | ブロックの追加（`sample.lightning`、`sample.random_color`）、コマンドの実装、同梱ゲームのインストール |
| `src/main/resources/games/sample_storm.jgame.json` | 追加したブロックを使うゲーム「サンダーストーム」 |
| `src/main/resources/maps/sample_plaza.jmap.json` | そのゲーム用のマップ（空のワールドに床をスクリプトで敷く） |
| `src/test/kotlin/.../SampleGameTest.kt` | 同梱ゲームが追加ブロック込みでコンパイルできることのテスト |

起動すると、ゲームとマップを JunkGamesCore の `games/` `maps/` にコピーします（既にあれば上書きしません。`/jgsample install` で上書き）。
`/play sample_storm` で遊べます（2人以上）。
