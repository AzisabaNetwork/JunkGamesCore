# junkgames-plugin

JunkGames サーバー用の個別プラグインをまとめて置くディレクトリです。プラグインごとにレポジトリを分けず、ここにディレクトリを追加します。

| ディレクトリ | プラグイン | 内容 |
|---|---|---|
| [`sample/`](sample) | JunkGamesSample | ブロックを追加するプラグインのひな形（ブロック追加・コマンド・ゲーム同梱・テスト） |
| [`sdk-sample/`](sdk-sample) | JunkGamesSdkSample | SDK（`JunkGame`）で作るゲームのひな形（ワールド宣言・SDK イベント・ゲーム内専用コマンド・GUI・チャット入力） |
| [`junk-survival/`](junk-survival) | JunkSurvival | めちゃくちゃサバイバル。SDK のゲームとして選択画面に出る（[AzisabaNetwork/JunkSurvival](https://github.com/AzisabaNetwork/JunkSurvival) から移行） |
| [`random-sound/`](random-sound) | RandomSound | ランダムサウンド（[AzisabaNetwork/RandomSound](https://github.com/AzisabaNetwork/RandomSound) から移行） |

## 新しいプラグインを追加する

1. プラグインで遊ぶゲームなら `sdk-sample/`、ブロックを追加するだけなら `sample/` をコピーして名前を変えます（例: `junkgames-plugin/my-game/`）。`build.gradle.kts` があるディレクトリは自動でビルド対象になります（`settings.gradle.kts` の編集は不要）。
2. `build.gradle.kts` の `version` と `archiveBaseName`、`plugin.yml` の `name` / `main`、パッケージ名（`net.azisaba.jg.plugin.<名前>`）を変えます。
3. ビルドします。

```bash
./gradlew :junkgames-plugin:my-game:build
```

```bash
./gradlew :junkgames-plugin:pluginJars
```

1つ目で `junkgames-plugin/my-game/build/libs/` に jar ができます。2つ目は全プラグインの jar を `build/plugins/` に集めます。

## 決まりごと

- **コマンドは `plugin.yml` の `commands:` に書きます。** 実装は `getCommand("…")!!.setExecutor(…)` で結び付けます（Brigadier の `registerCommand` だけで登録しない）。権限も `plugin.yml` に書きます。
- **`plugin.yml` に `depend: [JunkGamesCore]` を書きます。** Kotlin 標準ライブラリと `junkgames-api` は JunkGamesCore に入っているものを使うので、各プラグインの jar には含めません（`compileOnly`）。Kotlin を自分の jar に同梱すると、API の関数型が別のクラスになり動かなくなります。
- ブロックを追加するときは `BlockBuilder` を使い、`.owner(name)` を付けます。プラグインが無効になると、そのブロックは自動で登録解除されます。
- ブロックを使うゲームを同梱するなら、IR の `requires.addons` にプラグイン名を書きます。プラグインがないサーバーでは、そのゲームは無効になります。
- ゲームのテストは `testImplementation(project(":junkgames-core"))` を足すと、サーバーなしでコンパイルまで確認できます（`sample/src/test` を参照）。
