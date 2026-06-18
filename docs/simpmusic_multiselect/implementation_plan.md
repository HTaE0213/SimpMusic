# SimpMusic 複数選択機能・レイアウト・メタデータ改善計画

## 2026-06-19 追加実装計画
1. `contributorsAvatars` の応答型を追加し、初回・継続プレイリストパーサーで追加者情報を抽出する。
2. 追加者情報を `SongItem` から `Track` へ伝搬し、共通曲行の縦三点メニュー左側にアバターを表示する。
3. DataStoreにシークレット設定と履歴非表示曲IDを保持する。
4. 検索履歴、最近再生、再生回数、分析イベント、YouTube再生トラッキングをシークレット中に抑止する。キューと再生位置は保持する。
5. XML構文と差分を静的検査し、コンパイル診断後に実アカウントと実機で検証する。

## 2026-06-18 改訂実装計画
1. `SongFullWidthItems` の長押し選択を通常画面で既定有効にする。
2. `SharedViewModel` に選択中トラックと「すべて選択」用スコープを保持する。
3. `MultiSelectBottomBar` を `App.kt` の `Scaffold.bottomBar` へ集約する。
4. 選択中はグローバル `BackHandler` で選択解除を優先する。
5. 一括操作に「次に再生」とYouTubeプレイリスト追加を追加する。
6. JetBrains診断MCPまたはユーザーが明示許可したGradleタスクでコンパイル検証後、実機で操作順序とAPI結果を確認する。

> 以下の3画面個別配置案は過去計画であり、現在は上記のグローバル方式を採用する。

## User Review Required

### 1. 複数選択メニューとボトムナビゲーションバーの重なり修正
- **問題点**: 複数選択モードに入った際、一括操作バー（`MultiSelectBottomBar`）がメインの下部ナビゲーションバーと重なって隠れてしまう。
- **解決案**: `App.kt` にて `isSelectionMode` 状態を監視し、複数選択モード中はボトムナビゲーションバー自体を非表示にし、一括操作バーが画面最下部に綺麗に収まるようにします。

### 2. メタデータオリジナル言語表記への切り替え遅延（カクつき）の改善方針
- **問題点**: 現在はUI表示後に非同期で英語に書き換えるため、最初にカタカナが表示され、後からカクッと切り替わってしまいます。
- **改善アプローチの比較**: 後述の **「Open Questions」** を参照し、どの方針にするかご意見をください。

## Open Questions

> [!IMPORTANT]
> **メタデータのカクつき切り替えをどう改善するか、以下の4つのアプローチから選択してください。**
>
> **A. (推奨) ローカルキャッシュの永続化 + フェードアニメーション**:
> - 一度解決された曲はローカルのデータベースに保存され、2回目以降はアプリ再起動後も「最初から英語（オリジナル）」で即時に表示されます。
> - 初回（キャッシュなし）の切り替え時は、カタカナから英語への切り替え時にフェードアニメーション等を適用して、カクカクする違和感を和らげます。
> - *メリット*: 検索やリストロードの速度を落とさず（ロードの快適さを維持したまま）、使えば使うほど「最初からオリジナル表示」される曲が増えます。
>
> **B. リストロード時の事前一括並列解決 (Blocking)**:
> - 検索結果やプレイリストを取得した際、裏ですべてのカタカナ曲のメタデータ解決（通信）が完了するまで、リスト全体の表示を待機（Loadingを表示）させます。
> - *メリット*: 初回表示であっても、画面が出た瞬間から完璧にオリジナル表記で表示されます。
> - *デメリット*: 検索やプレイリストを開いてから結果が表示されるまでの「Loading...」時間が、毎回 **0.5秒〜1秒程度長く** なります。またAPIの同時接続数が増えます。
>
> **C. スケルトン（プレースホルダー）化**:
> - 解決が完了するまで、曲タイトル・アーティストの領域を「読み込み中」のプレースホルダー表示にし、解決が完了した瞬間にテキストを表示します。
> - *デメリット*: タイトルが読めるようになるまでに一瞬のラグが発生するため、一覧性が損なわれます。
>
> **D. 日本語・英語APIの2系統並列リクエストマージ法**:
> - 検索やプレイリストを開く際、裏で日本語(`hl=ja`)と英語(`hl=en`)の2つのリクエストを並列送信し、結果を `videoId` をキーにして合流させます。カタカナ翻訳されている曲のみ、英語データで上書きしてUIに渡します。
> - *メリット*: 初回表示からカクつきなくオリジナル表記で表示され、かつ個別に1曲ずつ通信しないため、リスト表示の待ち時間への影響がごく僅か（+100ms〜200ms程度）で済みます。
> - *デメリット*: 検索順位やリスト順が言語間で異なり照合できない曲があった場合、その曲はカタカナのまま表示されます。また実装範囲がやや広くなります。

---

## 複数選択機能の設計アプローチ比較

Rule 4 に従い、本機能の実装方法について技術的選択肢を比較検討し、推奨するアプローチを提案します。

| アプローチ | 概要 | メリット (Pros) | デメリット (Cons) |
| :--- | :--- | :--- | :--- |
| **オプションA (推奨)**<br>SharedViewModelによる共有管理 | `SharedViewModel` に選択モードフラグと `selectedTracks: Set<Track>` の状態を一元管理する。任意の画面の長押しで複数選択を開始でき、共通の一括操作バーを利用する。 | <ul><li>重複コードを大幅に削減できる。</li><li>全画面で一貫した操作性を提供可能。</li><li>一括処理ロジックを1箇所に集約できる。</li></ul> | <ul><li>画面遷移時に選択状態を明示的にクリアする制御が必要。</li><li>画面固有アクション（例: プレイリストからの削除）の呼び出しにデリゲート等の連携が必要。</li></ul> |
| **オプションB**<br>各画面のローカル状態管理 | 各画面（`PlaylistScreen`等）の Composable または個別 ViewModel で独立して `selectedIds: Set<String>` を管理する。 | <ul><li>各画面の結合度が低く、画面固有のアクションをクリーンに処理できる。</li><li>画面遷移時の状態クリア漏れが発生しない。</li></ul> | <ul><li>実装対象の画面数が多いため（7〜8画面）、膨大な重複コードが発生する。</li><li>一括操作バーのUIやチェックボックスの制御などを全画面に実装する必要があり、保守性が著しく低い。</li></ul> |

### 推奨アプローチと rationale
**オプションA** を強く推奨します。
SimpMusicにおいて、曲リストを表示する画面は非常に多岐にわたります（プレイリスト、ローカル、検索結果、アルバム、アーティスト、最近再生した曲など）。各画面で個別に複数選択の状態管理とUIを構築するのは非効率であり、コードが散乱する原因になります。`SharedViewModel` に選択状態とキュー/プレイリスト一括追加のロジックを集約することで、UI側は「選択状態のトグル」と「長押し長しジェスチャーの追加」のみで容易に複数選択に対応できます。

### 推奨オプションのリスクと対策
- **リスク**: 画面遷移時に選択状態が残ったままになり、別の画面で意図しない曲が選択され続ける可能性がある。
- **対策**: 各画面の Composable の `DisposableEffect` や、ナビゲーション遷移時に `sharedViewModel.clearSelection()` を呼び出し、明示的に選択モードをリセットする。

---

## 提案する変更

### 1. `SharedViewModel` での複数選択状態と一括処理ロジックの実装

#### [MODIFY] [SharedViewModel.kt](file:///d:/Document/Antigravity/music/composeApp/src/commonMain/kotlin/com/maxrave/simpmusic/viewModel/SharedViewModel.kt)
- 複数選択状態を管理する `isSelectionMode: StateFlow<Boolean>`、`selectedTracks: StateFlow<Set<Track>>` を追加。
- `toggleTrackSelection(track: Track)`: 選択状態のトグルおよび、選択数が0になった場合の選択モード自動解除。
- `selectAllTracks(tracks: List<Track>)`: 表示中の全曲を一括選択。
- `clearSelection()`: 選択解除および選択モードの終了。
- `addSelectedToQueue()`: 選択された `Set<Track>` を一括で再生キューに追加。
- `addSelectedToLocalPlaylist(playlistId: Long)`: 選択された曲をローカルプレイリストへ一括追加。

### 2. リストアイテム UI への選択表示 of 統合

#### [MODIFY] [FullWidthItems.kt](file:///d:/Document/Antigravity/music/composeApp/src/commonMain/kotlin/com/maxrave/simpmusic/ui/component/FullWidthItems.kt)
- `SongFullWidthItems` Composable において：
  - 長押し（Long Click）ジェスチャーを検知する修飾子を追加。
  - `SharedViewModel` から選択状態を読み込み、選択モード中 (`isSelectionMode == true`) はアイテムの左側にチェックボックスを表示。
  - 選択モード中は、通常のタップ動作を「選択/解除のトグル」に切り替える。

### 3. 共通一括操作バーの作成

#### [NEW] [MultiSelectBottomBar.kt](file:///d:/Document/Antigravity/music/composeApp/src/commonMain/kotlin/com/maxrave/simpmusic/ui/component/MultiSelectBottomBar.kt)
- 複数選択モードが有効な際に、画面最下部に出現するフローティングアクションバー。
- 以下のボタンを提供：
  - 「再生キューに追加」(Add to Queue)
  - 「ローカルプレイリストに追加」(Add to Local Playlist)（ボトムシートなどを開く）
  - 「すべて選択」(Select All) / 「すべて解除」(Clear)
  - 閉じるボタン（選択モードの終了）

### 4. 主要画面への適用とナビゲーションのクリーンアップ

#### [MODIFY] [App.kt](file:///d:/Document/Antigravity/music/composeApp/src/commonMain/kotlin/com/maxrave/simpmusic/App.kt)
- `viewModel.isSelectionMode` を監視する `isSelectionMode` 状態変数を定義。
- `bottomBar = { ... }` 内で、`!isSelectionMode` の時にボトムナビゲーションバー（`LiquidGlassAppBottomNavigationBar` / `AppBottomNavigationBar`）を描画するよう条件を追加。複数選択モード中はボトムバーを完全に非表示にします。

#### [MODIFY] [PlaylistScreen.kt](file:///d:/Document/Antigravity/music/composeApp/src/commonMain/kotlin/com/maxrave/simpmusic/ui/screen/other/PlaylistScreen.kt)
#### [MODIFY] [LocalPlaylistScreen.kt](file:///d:/Document/Antigravity/music/composeApp/src/commonMain/kotlin/com/maxrave/simpmusic/ui/screen/library/LocalPlaylistScreen.kt)
#### [MODIFY] [SearchScreen.kt](file:///d:/Document/Antigravity/music/composeApp/src/commonMain/kotlin/com/maxrave/simpmusic/ui/screen/other/SearchScreen.kt)
- 各画面のルートレイアウト（`Scaffold` 等）にて、`sharedViewModel.isSelectionMode` が true の場合に `MultiSelectBottomBar` を配置する。
- 画面遷移（`onBack` 等）のタイミングで `sharedViewModel.clearSelection()` を実行するようにライフサイクルを制御。

---

## 検証計画

### 自動テスト / コンパイル検証
- 修正完了後、`./gradlew :androidApp:assembleDebug` を実行しビルドが通ることを確認。

### 手動検証手順
1. アプリを起動し、任意のプレイリスト画面を開く。
2. リスト内の任意の曲を長押しし、複数選択モードに入る（左側にチェックボックスが出現し、画面下部に一括操作バーが表示されることを確認）。
3. 別の曲をタップして複数曲を選択する。
4. 下部バーの「再生キューに追加」をタップし、選択したすべての曲がキューに追加されることを確認。
5. 下部バー of 「ローカルプレイリストに追加」をタップし、選択したすべての曲が指定したプレイリストへ一括追加されることを確認。
6. 選択モードを終了または別画面へ遷移した際、選択状態がリセットされることを確認。
