# CobbleCatchCombo

Cobblemon 1.7.1用キャッチコンボMod（Fabric/Kotlin）

## ビルド・実行

```bash
./gradlew build          # ビルド
./gradlew runClient      # クライアント起動
```

## アーキテクチャ

```
src/main/kotlin/com/pokemon/catchcombo/
├── CobbleCatchCombo.kt          # エントリーポイント
├── config/                       # JSON5設定
├── data/                         # データ永続化（SQLite/MySQL/MongoDB）
├── service/                      # ビジネスロジック
├── spawn/                        # SpawningInfluence（シャイニー/IV補正）
├── event/                        # Cobblemonイベントハンドラ
├── display/                      # BossBar/ActionBar表示
├── command/                      # /catchcombo コマンド
├── lang/                         # 多言語対応
└── integration/                  # Text Placeholder API連携
```

## 重要なAPI

- **SpawningInfluence**: `PlayerSpawnerFactory.influenceBuilders`でスポーン前に補正
- **イベント**: `CobblemonEvents.POKEMON_CAPTURED`, `BATTLE_FLED`, `BATTLE_VICTORY`
- **種族ID**: `pokemon.species.resourceIdentifier.toString()` → `"cobblemon:pikachu"`

## コマンド

| コマンド | 権限 | 説明 |
|---------|------|------|
| `/catchcombo` | 0 | コンボ状態表示 |
| `/catchcombo reload` | 2 | 設定リロード |
| `/catchcombo reset` | 2 | コンボリセット |
| `/combo` | 0 | エイリアス |

## 設定ファイル

`config/cobblecatchcombo/config.json5`

## データベース対応

| タイプ | 設定値 | クロスサーバー | 説明 |
|--------|--------|----------------|------|
| SQLite | `sqlite` | ❌ | 単一サーバー向け（デフォルト） |
| MySQL | `mysql` | ✅ | HikariCP接続プール、マルチサーバー対応 |
| MongoDB | `mongodb` | ✅ | ドキュメントDB、マルチサーバー対応 |

## 設定オプション詳細

### 進化系統を同種扱い
`combo.treatEvolutionLineAsSameSpecies`を有効にすると、進化ラインが同じポケモンを同種として扱います。
- 例：ピチュー → ピカチュウ → ライチュウ は同じコンボとしてカウント
- イーブイ → ブイズ各種 も同じコンボとしてカウント

### ActionBar表示時間
`actionBar.showDurationSeconds`で指定した秒数、ActionBarを表示し続けます。
Minecraftの自動フェード（約2秒）を回避するため、定期的に再送信しています。
