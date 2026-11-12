# JunkGamesCore

[アジ鯖](https://www.azisaba.net) のミニゲームサーバー **JunkGames** の基幹プラグインです。次の2つを提供します。

- **ハブと SDK**（0.x から引き継いだ機能）: ロビー、ゲームの選択画面（`/junkgames`）、ゲームごとのワールド分割、
  プラグインでゲームを作るための SDK（`JunkGame`、独自イベント、ゲーム内専用コマンド、GUI・チャット入力などのユーティリティ）
- **ブロック式ミニゲームエンジン**: Webのブロックエディタ（Scratch風）で組み立てたゲームのロジックを中間表現（IR, JSON）として受け取り、
  検証・コンパイル・実行します。**コードを書かずにミニゲームを量産する**ことが目的です。

プラグインで作ったゲームとブロックで作ったゲームは、同じ選択画面に並びます。

```yaml
Version: 1.0.0-SNAPSHOT
Server: Paper 26.3 / Folia / Minogami
Language: Kotlin 2.4 (JVM 25)
```

設計の元になった仕様書は「ブロック式ミニゲームエンジン 仕様書 v0.2」です。本文中の §番号はその節を指します。

## モジュール構成

| モジュール | 内容 | 依存のさせ方 |
|---|---|---|
| `junkgames-platform-api` | Capability / Provider SPI（§12）。インターフェースとデータ型のみ | サーバーフォーク（Minogami）が `compileOnly` で依存し、Native Provider を実装する |
| `junkgames-api` | ゲーム SDK（`net.azisaba.jg.sdk` ほか、0.x と同じパッケージ名）、ブロックSPI（§6.3）、IRモデル、型、実行時コンテキスト | ゲーム・アドオンのプラグインが `compileOnly` で依存する |
| `junkgames-core` | プラグイン本体（`JunkGamesCore.jar`）。Kotlin 標準ライブラリを同梱 | サーバーに入れる |
| `junkgames-plugin/*` | JunkGames 用の個別プラグイン（ブロック追加のサンプル、SDK のサンプル、JunkSurvival、RandomSound） | それぞれの jar をサーバーに入れる。詳しくは [junkgames-plugin/README.md](junkgames-plugin/README.md) |

```
net.azisaba.jg
├── JunkGames       0.x 互換の入口（JunkGames.getPlugin() / lobby / spawn / random）
├── sdk             JunkGame, IJunkGame, JunkGameCommand, JunkGamesSdk, event/（PlayerJoin/Quit/ChatEvent, EventEngine）
├── ui              InventoryUI, CLI
├── user            IUser, Sara（皿・ロール）
├── util            Typing, ComponentUtility, PlayerUtility, ResourceUtility, SqlUtility, UserUtility, MathUtility, UuidUtility
├── platform        Provider SPI（world / structure / view / tick / execution）
├── api
│   ├── ir          GameDefinition, MapDefinition, IrNode, Diagnostic …
│   ├── type        ValueType（number, player, list<T> …）
│   ├── runtime     Expr / Stmt / Frame / Exec / GameContext / TriggerSpec / Values
│   └── block       BlockDefinition, BlockBuilder, BlockRegistry, CompileContext
└── core
    ├── ir          IrParser（スキーマ検証・マイグレーション）
    ├── compile     GameCompiler（型検査つき IR → ノード木）, Literals, CompiledGame
    ├── runtime     Runner（コルーチンVM）, InstanceRuntime（スケジューラ・実行予算）
    ├── block       BlockRegistryImpl, BlockExporter（エディタ用定義の自動生成）, std/（標準ブロック）
    ├── instance    GameInstance（ライフサイクル）, InstanceManager（キュー・調停役）, PlayerSnapshot
    ├── provider    CapabilityRegistry, paper/（Paper標準APIによるフォールバック）
    ├── hub         Hub（ロビー・SDK ゲームの登録）, JunkGamesMenu（選択画面）, HubListener（チャット・ワールド移動・SDK イベント）
    ├── definition  DefinitionStore（定義の読み込み・版の切り替え）
    ├── map         MapEditor（ゲーム内マップ設定ツール）
    ├── listener    ArenaListener（Bukkitイベント → トリガー）
    └── command     /play, /leave, /jg, /jgmap
```

## 実装状況（仕様書のフェーズとの対応）

| フェーズ | 内容 | 状況 |
|---|---|---|
| P0 | IRスキーマ・コンパイラ・ランタイム | ✅ 実装済み。サンプルの TNTラン を同梱 |
| P1 | インスタンス管理・ロビー・マップツール | ✅ 実装済み（キュー、ワールド複製、プレイヤー隔離、`/jgmap`） |
| P2〜P4 | 定義リポジトリ・Webエディタ・テストプレイ・版管理 | ⬜ 未着手。コア側の受け口（`DefinitionStore`、`/jgadmin export-blocks`、診断のノードID）は用意済み |
| P5 | アドオンSPI | ✅ `junkgames-api` として公開 |
| P6 | Provider SPI と Paper フォールバック | ✅ SPI・選択・縮退・Paper/Folia 実装。外部プラグイン（Multiverse・FAWE・PacketEvents）の Provider と TCK は未着手 |
| P7 | Minogami の Native Provider | ⬜ フォーク側の作業。SPI（`ExecutionProvider.scheduleWorldTick` など）は用意済み |
| P8〜P9 | AI生成 | ⬜ 未着手（リポジトリ側で実装する想定） |

## 導入

1. `./gradlew build` で `junkgames-core/build/libs/JunkGamesCore-<version>.jar` ができます（JDK 25 以上）。
2. サーバーの `plugins/` に入れて起動します。Kotlin 標準ライブラリは JunkGamesCore の jar に入っており、`junkgames-plugin/` のプラグインはそれを使います（`depend: [JunkGamesCore]`）。
3. 初回起動時、`plugins/JunkGamesCore/` に以下が作られます。

```
plugins/JunkGamesCore/
├── config.yml          予算・上限・同時開催数・ロビー
├── providers.yml       能力ごとの Provider の優先順
├── games/              *.jgame.json（ゲーム定義。サンプル: tnt_run）
├── maps/               *.jmap.json（マップ定義。サンプル: tnt_run_classic）
├── templates/<map>/    テンプレートワールド（/jgmap save で保存される）
├── schematics/         ストラクチャー（.nbt）
├── schema/             game.schema.json / map.schema.json（エディタ・リポジトリと共有）
├── snapshots/          参加中プレイヤーの持ち物の退避（異常終了時の復元用）
├── export/blocks.json  エディタ用のブロック定義（自動生成）
└── logs/errors.log     実行時エラー（JSON Lines、ノードID付き）
```

サンプルの TNTラン は、テンプレートワールドがなくても空のワールドを作り、床をスクリプトで敷くので、そのまま `/play tnt_run` で遊べます。

## コマンド

| コマンド | 権限 | 説明 |
|---|---|---|
| `/junkgames [game] [map]`（`/jg`） | `junkgames.play` | 引数なしでゲームの選択画面。名前を付けると、そのゲーム（SDK・ブロックどちらでも）に接続 |
| `/<game>`（`/junkgames:<game>`） | | SDK のゲームへの接続（0.x と同じ。ゲームの登録時に自動で作られる） |
| `/play [game] [map]` | `junkgames.play` | 引数なしで遊べるゲームの一覧。マップ省略時は互換マップからランダム |
| `/leave` | `junkgames.play` | 試合・待ち列から抜ける |
| `/jgadmin list \| info <game> \| validate <game>` | `junkgames.admin` | 定義の一覧・詳細・再検証（エラーはノードID付き） |
| `/jgadmin instances \| start <id> \| stop <id> \| debug <id>` | `junkgames.admin` | 試合の一覧（ランナー数・ステップ数・エラー数）、強制開始・終了、エラー表示 |
| `/jgadmin reload \| export-blocks \| providers \| errors` | `junkgames.admin` | 再読み込み、エディタ用定義の書き出し、選ばれた Provider、最近のエラー |
| `/jgmap edit <map>` | `junkgames.map` | テンプレートの作業コピーに入って編集を始める |
| `/jgmap point add\|clear <name>` | | 立っている位置を地点として登録（複数可） |
| `/jgmap region add\|clear <name>` | | ワンドの pos1（左クリック）/ pos2（右クリック）の直方体を領域に追加 |
| `/jgmap game add <game>` / `info` / `save` / `exit` | | 対応ゲームの追加、要件チェック、保存（定義＋テンプレート）、終了 |

## ハブと SDK（0.x から引き継いだ機能）

0.x（Spigot 1.17、Java）の機能を Kotlin で作り直し、パッケージ名・クラス名・メソッド名はそのまま残しています。
0.x 向けに Java で書かれたゲームプラグインは、ソースをほぼ変えずにビルドできます（`junkgames-api/src/test/java` の互換テストで確認しています）。
`plugin.yml` で `provides: [JunkGames]` を宣言しているので、`depend: [JunkGames]` もそのまま使えます。

- **ロビー**: `config.yml` の `lobby`（既定はワールド `lobby`、ない場合は空のワールドを作成）。参加時にロビーのスポーンへ移動し、ロビーでは満腹度が減りません。0.x の `spawn:` 設定も読みます。
- **選択画面**（`/junkgames`）: メインロビー、ゲーム一覧（「クリックして接続」/ PLAYING、プレイ人数）、ランダムなゲーム、閉じる、通報。SDK のゲームとブロック製のゲームが優先度順に並びます。ブロック製のゲームの見た目は IR の `meta.favicon` / `category` / `lore` / `priority` で指定します。
- **ワールド分割**: SDK のゲームは `addWorld` で使うワールドを宣言し、そこにいるプレイヤーがそのゲームの参加者になります。
- **チャット**: ブロック製の試合中はその試合の中だけ、SDK のゲーム内はそのゲームのワールドの中だけ（SDK の `PlayerChatEvent` も発生）、それ以外は同じワールドの中だけに届きます。`Typing` で入力待ちのプレイヤーの発言は、送信されずに `Typing#onTyped` に渡ります。
- **接続コマンド**: SDK のゲームが登録されると、0.x と同じく `/<ゲーム名>`（`/junkgames:<ゲーム名>`）が自動で登録されます。実行すると `onJunkGameCommand` が呼ばれます（既に参加中なら案内のみ）。選択画面のクリックもこのコマンドを実行します。これはゲームの数だけ存在するため、`plugin.yml` で宣言しない唯一のコマンドです。
- **ゲーム内専用コマンド**: `JunkGameCommand` を継承したコマンドはそのゲームの中でしか使えず、外ではタブ補完にも出ません。

```kotlin
class Example : JunkGame() {
    override val displayName = Component.text("Example", NamedTextColor.GREEN)
    override val favicon = Material.DIAMOND
    override val category = "PvP"
    override val lore = listOf(Component.text("説明", NamedTextColor.GRAY))
    override val priority = 10

    // 選択画面・/junkgames Example で呼ばれる「接続」処理
    override fun onJunkGameCommand(player: Player) { player.teleportAsync(...) }

    override fun onEnable() {
        addWorld("example")
        addListener(ExampleListener()) // JunkGameListener（@JunkGameHandler fun onJoin(e: PlayerJoinEvent) …）
    }
}
```

完全な例は [`junkgames-plugin/sdk-sample`](junkgames-plugin/sdk-sample)（`InventoryUI`・`Typing`・`JunkGameCommand` を含む）と、SDK で動く [`junkgames-plugin/junk-survival`](junkgames-plugin/junk-survival) です。

### 0.x からの変更点

| 項目 | 0.x | 現在 |
|---|---|---|
| プラグイン名 | JunkGames | JunkGamesCore（`provides: [JunkGames]`） |
| ゲームへの接続コマンド | ゲームごとに `/<ゲーム名>`（`/junkgames:<ゲーム名>`）を実行時に登録 | 同じ（維持）。加えて `/junkgames <ゲーム名>` でも接続できる |
| 選択画面 | すべてのゲームが同じ枠に重なって表示されていた | ゲームごとの枠に表示。ブロック製のゲームも表示 |
| ロビーのチャット | 受け取った人自身の名前で表示されていた | 発言者の名前で表示 |
| SDK イベント | クラスが完全に一致するハンドラだけ呼ばれ、例外は握りつぶされていた | 親クラスのハンドラも呼ばれ、例外はログに出る |
| 同じゲームのワールド間の移動 | 退出と参加のイベントが両方発生 | 発生しない |
| `addWorld(String)` | その時点で読み込まれていないワールドは無視 | 後から読み込まれたワールドも対象 |
| `Typing#onTyped` | 非同期のチャットスレッドで呼ばれた | プレイヤーのスレッドで呼ばれる |
| `ResourceUtility.exists` | 常に false を返していた | 正しく判定 |
| `JunkGame.getPlugin()` | 最後に作られたゲームを返す（複数あると取り違える） | 同じ動作だが非推奨 |

## ゲームの書き方（IR）

エディタができるまでは JSON を直接書きます（P0 と同じ運用）。形式は §5 の通りで、`schema/game.schema.json` で検証できます。

```json
{
  "id": "s_floor",
  "trigger": { "type": "event.player_move_onto_block", "fields": { "filter": "in_region" },
               "inputs": { "region": { "lit": { "regionRef": "floor" } } } },
  "body": [
    { "id": "n_1", "type": "control.wait_ticks", "inputs": { "ticks": { "lit": 8 } } },
    { "id": "n_2", "type": "world.set_block",
      "inputs": { "location": { "type": "event.block_location" },
                  "block": { "lit": { "material": "minecraft:air" } } } }
  ]
}
```

- ライフサイクル（待機 → カウントダウン → 試合 → 結果表示 → リセット）、ロビー、チーム分け、持ち物の退避と復元、チャットとスコアボードの分離はコアが行います。ブロックで書くのはゲームのルールだけです。
- `meta.rules`（`blockBreak` / `blockPlace` / `pvp` / `hunger` / `fallDamage` / `itemDrop` / `naturalRegen`）で既定のルールを決め、スクリプトの `event.cancel` / `event.allow` で個別に上書きできます。
- 使えるブロックの一覧は `/jgadmin export-blocks` の出力（`export/blocks.json`）を見てください。ラベル・入力の型・既定値・Blockly 用の定義が入っています。

### 実行モデル（§7）

- 1回の発火 = 1つの `Runner`（スレッドを持たないコルーチン）。待機ブロックで中断し、次のティック以降に再開します。
- ノード1つの評価が1ステップ。ランナーごと 1,000 ステップ/tick、インスタンスごと 20,000 ステップ/tick。200 tick 連続で使い切ったランナーは暴走として停止します。
- ループは既定で1反復ごとに1tick譲ります（`meta.loopYield`）。
- キャンセルできるイベントは、ハンドラが最初に待機するまでの同期区間でのみキャンセルできます。
- 実行時エラーはそのランナーだけを止め、ノードID付きで記録します。1分間に10回を超えると試合を無効にします。

## アドオンでブロックを追加する（§6.3）

プラグインはこのレポジトリの `junkgames-plugin/` に置きます。ひな形は [`junkgames-plugin/sample`](junkgames-plugin/sample) です（ブロック追加、`plugin.yml` のコマンド、ゲームの同梱、テスト）。

```kotlin
// junkgames-plugin/<name>/ — junkgames-api は compileOnly で自動的に追加される
// plugin.yml: depend: [JunkGamesCore]、コマンドは commands: に書く
class MyAddon : JavaPlugin() {
    override fun onEnable() {
        JunkGamesApi.get().blocks.register(this,
            BlockBuilder.stack("myaddon.lightning")
                .category(Category.WORLD)
                .label("%location に雷を落とす", "strike lightning at %location")
                .input("location", ValueType.LOCATION)
                .exec { _, a -> a.location("location")?.let { it.world.strikeLightningEffect(it) } })
    }
}
```

登録されたブロックは、エディタ用の定義（`export/blocks.json`）に自動で含まれ、読み込み済みのゲームは再コンパイルされます。
IR の `requires.addons` に書いたアドオンがないサーバーでは、そのゲームは無効になります。

## 外部連携と Minogami（§12）

`providers.yml` の順に、正常な Provider を能力ごとに1つ選びます（`/jgadmin providers` で確認できます）。

| 能力 | 同梱の Provider | 備考 |
|---|---|---|
| `execution` | `paper`（メインスレッド）、`folia`（リージョン） | Minogami の Native Provider が `PER_WORLD_PARALLEL` を提供すると、1試合＝1ワールド＝1スレッドで並列に動く |
| `world` | `paper`（WorldCreator＋ファイル複製） | 26.x の `world/dimensions/<ns>/<path>/` 構成に対応 |
| `structure` | `paper`（`.nbt`、分割fill、スナップショット） | `.schem` は FAWE/WorldEdit の Provider が必要 |
| `view` | `paper`（偽ブロック、エンティティ非表示） | プレイヤーごとの発光は PacketEvents / Native が必要（`view.glow` はグレーアウト） |

### Minogami で動かす場合

1. フォーク側で `junkgames-platform-api` に依存し、`ExecutionProvider`（`name = "native"`、`threading = PER_WORLD_PARALLEL`）を実装して、起動時に `ServicesManager` に登録します。`scheduleWorldTick` は「指定ワールドの WorldThread 上で毎ティック呼ばれるフック」、`handoff` は「相手ワールドの次のティックで実行」です。
2. `minogami.yml` でコアのリスナーをイベントロックから外します。

```yaml
thread-safety:
  event-lock-bypass:
    plugins:
      - JunkGamesCore
```

コアは、インスタンスの状態をそのワールドのスレッドからだけ触り、ワールドをまたぐ処理（ロビーへの帰還など）はハンドオフで行い、ティック内では I/O をしません（ファイル書き込みは専用スレッドに積みます）。Native Provider がない Minogami では、警告を出してメインスレッドのスケジューラで動きます。

## 開発

```bash
./gradlew build
```

```bash
./gradlew :junkgames-plugin:pluginJars
```

1つ目で Core と全プラグインをビルド・テストし、2つ目で `junkgames-plugin/` のプラグインの jar を `build/plugins/` に集めます。

コマンドは `plugin.yml` に宣言します（Brigadier の `registerCommand` だけで登録しない）。例外は SDK ゲームの接続コマンド `/<game>`（`/junkgames:<game>`）で、0.x との互換のため実行時に登録します。

`junkgames-core/src/test` に、Minecraft サーバーなしで動くコンパイラ・ランタイムのテスト（再帰、暴走検出、予算の持ち越し、メッセージ送信と待機など）があります。
