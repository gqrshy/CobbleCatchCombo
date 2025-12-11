# CobbleCatchCombo

Cobblemon 1.7.1用キャッチコンボMod（Fabric/Kotlin）

## ビルド・実行

```bash
./gradlew build          # ビルド
./gradlew runClient      # クライアント起動
```

---

## 🧠 コーディングルール（必読）

このプロジェクトで作業する際は以下を厳守してください：

### 差分指向
- 既存コードの意図を尊重し、**必要最小限の変更**にとどめる
- 不要なリファクタ・スタイル変更・抽象化の追加は行わない
- まるでGitの差分コミットのように最小限の変更を心がける

### コーディングスタイル
- 言語: **Kotlin** (JVM 21)
- インデント: スペース4つ
- 命名規則: camelCase（関数・変数）、PascalCase（クラス）
- 既存のutil/関数があれば再利用する（新規作成より優先）
- 新しい依存関係の追加は慎重に（既存の依存で解決できないか検討）

### API調査必須
- Cobblemon APIがわからない場合は必ず**1.7.1のソースコード**を確認
- 参考リポジトリ: https://gitlab.com/cable-mc/cobblemon (1.7.1タグ)
- Cobblemon Unchainedの実装も参考に: https://github.com/timinc-cobble/cobblemon-unchained-1.5-fabric

---

## アーキテクチャ概要

### 技術スタック
| 項目 | 技術 |
|------|------|
| 言語 | Kotlin 2.0 |
| フレームワーク | Fabric 1.21.1 |
| 前提Mod | Cobblemon 1.7.1 |
| 設定形式 | JSON5 (kotlinx.serialization) |
| DB | SQLite / MySQL (HikariCP) / MongoDB |

### データフロー
```
[ポケモン捕獲] → CobblemonEventHandlers → ComboManager → Repository(DB)
                                              ↓
[スポーン発生] → CatchComboSpawnInfluence ← BonusCalculator
                          ↓
              [シャイニー/IV補正をpropsに設定]
```

### フォルダ構造
```
src/main/kotlin/com/pokemon/catchcombo/
├── CobbleCatchCombo.kt          # エントリーポイント、依存性注入
├── config/
│   ├── CatchComboConfig.kt      # 設定データクラス
│   └── ConfigManager.kt         # 設定読み書き、バリデーション
├── data/
│   ├── ComboRepository.kt       # リポジトリインターフェース
│   ├── SQLiteRepository.kt      # SQLite実装
│   ├── MySQLRepository.kt       # MySQL実装 (HikariCP)
│   ├── MongoDBRepository.kt     # MongoDB実装
│   └── RepositoryFactory.kt     # ファクトリ
├── service/
│   ├── ComboManager.kt          # コンボ状態管理（コア）
│   └── BonusCalculator.kt       # ボーナス計算
├── spawn/
│   ├── CatchComboSpawnInfluence.kt  # SpawningInfluence実装
│   └── SpawnInfluenceRegistrar.kt   # 登録処理
├── event/
│   └── CobblemonEventHandlers.kt    # イベントハンドラ
├── display/
│   └── DisplayManager.kt        # BossBar/ActionBar表示
├── command/
│   └── CatchComboCommands.kt    # コマンド定義
├── lang/
│   └── LanguageManager.kt       # 多言語対応
├── util/
│   └── SpeciesUtils.kt          # 種族関連ユーティリティ
└── integration/
    └── PlaceholderApiIntegration.kt  # Text Placeholder API連携
```

---

## 主要コンポーネント詳細

### 1. ComboManager (`service/ComboManager.kt`)
**役割**: コンボ状態の管理（キャプチャ、リセット、取得）

```kotlin
// 主要メソッド
fun onCapture(playerUuid: UUID, speciesId: String): CaptureResult
fun resetCombo(playerUuid: UUID): ResetResult?
fun getComboCount(playerUuid: UUID): Int
fun getChainedSpecies(playerUuid: UUID): String?  // nullable!
```

**注意点**:
- `getChainedSpecies()`はnullを返す可能性あり（nullチェック必須）
- 設定の`treatEvolutionLineAsSameSpecies`で進化系統比較が有効化される

### 2. CatchComboSpawnInfluence (`spawn/CatchComboSpawnInfluence.kt`)
**役割**: スポーン時のシャイニー/IV補正

```kotlin
// SpawningInfluenceインターフェース実装
override fun affectAction(action: SpawnAction<*>) {
    // PokemonSpawnActionのみ処理
    // action.props.shiny / action.props.ivs を設定（Pokemon生成前）
}
```

**重要**:
- `action.props`への設定はPokemon生成**前**に行われる
- 二重ロール防止のため、shinyは常に設定（true/false両方）

### 3. DisplayManager (`display/DisplayManager.kt`)
**役割**: BossBar/ActionBar表示制御

**注意点**:
- BossBar.StyleはMinecraftでは`NOTCHED_X`（設定の`SEGMENTED_X`から変換）
- ActionBarは自動フェード対策で定期的に再送信

### 4. SpeciesUtils (`util/SpeciesUtils.kt`)
**役割**: 種族ID解決、進化系統判定

```kotlin
fun formatSpeciesName(speciesId: String): String
fun getBaseSpecies(speciesId: String): String  // 進化系統の基本種
fun areInSameEvolutionLine(speciesId1: String, speciesId2: String): Boolean
```

---

## Cobblemon API リファレンス

### イベント
```kotlin
// 捕獲イベント
CobblemonEvents.POKEMON_CAPTURED.subscribe(Priority.NORMAL) { event ->
    event.player    // ServerPlayerEntity
    event.pokemon   // Pokemon
}

// 逃走イベント
CobblemonEvents.BATTLE_FLED.subscribe(Priority.NORMAL) { event ->
    event.player    // PlayerBattleActor (.entity でServerPlayerEntity取得)
    event.battle    // PokemonBattle
}

// 勝利イベント
CobblemonEvents.BATTLE_VICTORY.subscribe(Priority.NORMAL) { event ->
    event.winners       // List<BattleActor>
    event.losers        // List<BattleActor>
    event.wasWildCapture // Boolean
    event.battle        // PokemonBattle
}
```

### SpawningInfluence
```kotlin
// 登録
PlayerSpawnerFactory.influenceBuilders.add { player ->
    listOf(CatchComboSpawnInfluence(player))
}

// affectAction内で設定
action.props.shiny = true/false  // シャイニー設定
action.props.ivs = IVs.createRandomIVs(guaranteedPerfectIVs)  // IV設定
```

### 種族ID
```kotlin
// 捕獲時
pokemon.species.resourceIdentifier.toString()  // → "cobblemon:pikachu"

// スポーン時（SpawnDetail）
action.detail.pokemon.species  // → "pikachu" (namespace無し)

// 種族検索
PokemonSpecies.getByName("pikachu")
PokemonSpecies.getByIdentifier(Identifier.tryParse("cobblemon:pikachu"))

// 進化系統
species.preEvolution?.species  // PreEvolution?のspeciesプロパティ
species.evolutions             // MutableSet<Evolution>
```

---

## 設定ファイル

パス: `config/cobblecatchcombo/config.json5`

### 主要設定
| 設定キー | 型 | デフォルト | 説明 |
|---------|---|---------|------|
| `combo.treatEvolutionLineAsSameSpecies` | Boolean | false | 進化系統を同種扱い |
| `combo.resetOnPlayerFlee` | Boolean | true | 逃走時リセット |
| `shinyBoost.onlyChainedSpecies` | Boolean | true | コンボ種のみシャイニー補正 |
| `ivBoost.onlyChainedSpecies` | Boolean | true | コンボ種のみIV補正 |
| `display.bossBar.style` | String | "SEGMENTED_10" | NOTCHED_Xに変換される |
| `display.actionBar.showDurationSeconds` | Int | 5 | 表示時間（秒） |

---

## データベース対応

| タイプ | 設定値 | クロスサーバー | 説明 |
|--------|--------|----------------|------|
| SQLite | `sqlite` | ❌ | 単一サーバー向け（デフォルト） |
| MySQL | `mysql` | ✅ | HikariCP接続プール、SSL対応 |
| MongoDB | `mongodb` | ✅ | ドキュメントDB |

**注意**: DBタイプ変更は再起動が必要（ホットリロード不可）

---

## コマンド

| コマンド | 権限 | 説明 |
|---------|------|------|
| `/catchcombo` | 0 | コンボ状態表示 |
| `/catchcombo reload` | 2 | 設定リロード |
| `/catchcombo reset [player]` | 2 | コンボリセット |
| `/combo` | 0 | エイリアス |

---

## デバッグ手順

問題が発生した場合、以下の手順で診断：

1. **ログ確認**: `CobbleCatchCombo.LOGGER.debug()` の出力を確認
2. **設定検証**: `ConfigManager.validateConfig()` の警告を確認
3. **API確認**: Cobblemon 1.7.1のソースで該当APIを確認
4. **原因特定**: 「最もあり得る原因トップ3」を列挙してから修正

---

## 変更時のチェックリスト

- [ ] 既存コードの意図を理解したか
- [ ] 最小限の変更になっているか
- [ ] nullチェックは適切か（特に`getChainedSpecies()`）
- [ ] Cobblemon APIの使い方は正しいか（ソース確認済み）
- [ ] 設定リロードに対応しているか（`get()` で動的取得）
- [ ] スレッドセーフティは考慮されているか（ConcurrentHashMap等）
