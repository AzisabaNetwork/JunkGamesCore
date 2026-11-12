# RandomSound

- サーバーに参加して7秒後に、ランダムな音が鳴る
- リズムワールド（`config.yml` の `rhythm-world`、既定は `RhythmWorld`）で棒を持って左クリックすると、ランダムな音が鳴り、音の名前がアクションバーに出る

[AzisabaNetwork/RandomSound](https://github.com/AzisabaNetwork/RandomSound)（Spigot 1.17、作者: 46kuri_）を Kotlin / Paper 26.3 に移植したものです。

## 旧版からの変更点

| 項目 | 旧版 | 移行後 |
|---|---|---|
| 参加時の音 | `java.util.Timer` の別スレッドから鳴らしていた | プレイヤーのスケジューラで鳴らす（Folia・Minogami でも安全） |
| 左クリックの判定 | 右クリック以外（感圧板を踏んだときなども）で鳴っていた | 左クリックだけ |
| 音の一覧 | `Sound.values()`（26.x ではレジストリになった） | サウンドのレジストリから取得。除外する音は `config.yml` |
| ワールド名・待ち時間 | ソースに固定 | `config.yml` |
