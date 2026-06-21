# SimpMusic 実機検証フィードバックに基づくレイアウト修正計画

## 概要
ADB接続された実機Android端末での検証により判明した、以下のUIレイアウトおよび導線上の問題を解決します。

1. **縦画面におけるYouTubeプレイリスト並べ替え編集ボタンの不足**:
   - `PlaylistScreen.kt` 内で、並び替えモード（`changingOrder = true`）に移行するための「Edit / Done」ボタンが、横画面（`isMobilePortrait == false`）のヘッダーにしか配置されておらず、縦画面では並び替えモードに入ることができません。
   - **対策**: 縦画面（`isMobilePortrait == true`）の右上 `liquidGlass` アクション `Row` 内に、「Edit / Done」トグルボタンを追加します。

2. **Now Playing（再生中）画面最下部のクイック設定ボタンの描画問題**:
   - `NowPlayingScreen.kt` の最下部 `Row` 内に配置したシークレット（`quick_incognito`）およびサビメドレー（`quick_highlight_medley`）トグルボタンが、画面幅の制限や縦方向の高さ圧迫によりダンプツリーに存在しない（画面外にはみ出してクリップまたは非表示になっている）状態です。
   - **対策**: クイック設定トグルボタンを背景・テキスト付きの `TextButton` から、コンパクトな状態表示付き `IconButton` に変更します。これにより、横幅と高さを劇的に削減し、画面サイズが小さい端末でも確実に表示されるようにします。

## Proposed Changes

### UI Components

---

#### [MODIFY] [PlaylistScreen.kt](file:///d:/Document/Antigravity/music/composeApp/src/commonMain/kotlin/com/maxrave/simpmusic/ui/screen/other/PlaylistScreen.kt)
- 縦画面（`isMobilePortrait == true`）のヘッダー右上 `liquidGlass` の `Row` 内、SearchボタンとMoreボタンの間に、「Edit / Done」トグル用の `IconButton` を追加します。
- コード例:
  ```kotlin
  if (isYourYouTubePlaylist) {
      IconButton(
          enabled = !playlistEditing,
          onClick = {
              changingOrder = !changingOrder
              showSearchBar = false
              query = ""
          },
      ) {
          Icon(
              imageVector = if (changingOrder) Icons.Rounded.Done else Icons.Rounded.Edit,
              contentDescription = stringResource(Res.string.edit),
              tint = Color.White,
          )
      }
  }
  ```

---

#### [MODIFY] [NowPlayingScreen.kt](file:///d:/Document/Antigravity/music/composeApp/src/commonMain/kotlin/com/maxrave/simpmusic/ui/screen/player/NowPlayingScreen.kt)
- 最下部 `Row` のシークレットとサビメドレーボタンを、幅を取る `TextButton` から、状態に応じてアイコンや色が変わるコンパクトな `IconButton` に変更します。
  - **シークレットトグル (`quick_incognito`)**:
    - 有効時: `Icons.Rounded.VisibilityOff`, tint = `sliderTrackColor`, background = `sliderTrackColor.copy(alpha = 0.28f)`
    - 無効時: `Icons.Rounded.Visibility`, tint = `Color.White.copy(alpha = 0.6f)`, background = `Color.Transparent`
  - **サビメドレートグル (`quick_highlight_medley`)**:
    - 有効時: `Icons.Filled.AutoAwesome`, tint = `sliderTrackColor`, background = `sliderTrackColor.copy(alpha = 0.28f)`
    - 無効時: `Icons.Filled.AutoAwesome`, tint = `Color.White.copy(alpha = 0.6f)`, background = `Color.Transparent`

## Verification Plan

### Automated Tests
- `./gradlew :androidApp:assembleDebug --no-daemon -Pkotlin.incremental=false` を実行し、ビルドが成功することを確認します。

### Manual Verification
1. 修正版APKを実機Android端末にインストールします。
2. ライブラリ画面から所有YouTubeプレイリストを開き、ヘッダー右上に「ペンマーク（編集）」ボタンが表示されることを確認します。
3. 編集ボタンをタップして並び替えモードに入り、曲を長押しドラッグして並び替え、順序が保持されることを確認します。
4. 再生画面（Now Playing）を開き、最下部にシークレットモード（目のマーク）およびサビメドレー（キラキラマーク）のトグルボタンが表示されることを確認します。
5. 各トグルボタンをタップし、状態が切り替わる（アクティブ時に色が変わり、シークレットやサビメドレー機能が有効になる）ことを確認します。
# 2026-06-21 実機フィードバック再修正

1. `BrowseResponse` の編集可能ヘッダーを `PlaylistBrowse`、`PlaylistState` へ伝播する。
2. `PlaylistScreen` はAPI由来フラグと既存遷移フラグの論理和で編集ボタンを表示する。
3. ドラッグ検出を `LazyColumn` 全体から各曲の48dpハンドルへ移し、即時ドラッグにする。
4. `DragDropState` が開始位置と挿入候補位置を公開し、行強調と挿入ラインへ使用する。
5. 初期プレイリストパーサーで `contributorsAvatars` を `Track.addedBy` へ変換する。
6. YouTube全体ロケールを端末設定へ戻し、英語応答は明示的マージに限定する。

## 2026-06-21 連続並べ替え

1. ドラッグ行の中心が元の行領域へ戻ったら、保持中の挿入候補を消去する。
2. 原位置で終了した場合はViewModelコールバック、API、Toastをすべて発生させない。
3. UI順序を即時更新し、`Channel.UNLIMITED`へ`trySend`で同期投入する。
4. 単一consumerで移動APIをFIFO処理し、保留件数0でToastを1回表示する。
5. API失敗後は残りのindex操作を送らず、バッチ終了時にサーバー順序を再取得する。
# 2026-06-21 固定編集完了操作

1. 既存の折り畳み後TopAppBarを再利用する。
2. 編集中は検索アイコンを完了アイコンへ切り替える。
3. 完了操作で`changingOrder`を解除し、通常状態では従来どおり検索を表示する。
4. 下方スクロール時の表示、タップ、検索への復帰を実機確認する。
# 2026-06-22 再生画面ヘッダーとステータスバー

1. 既存のシークレット・ハイライトStateFlowとsetterをそのまま再利用する。
2. Now Playing TopAppBar actionsへ状態付きIconButtonを追加する。
3. 下部操作列から同じ2つのIconButtonを削除する。
4. AndroidのstatusBarStyle scrimを透明から不透明黒へ変更する。
5. 小画面のタイトル幅、状態色、システムバー表示を実機確認する。
# 2026-06-22 3ボタンナビゲーションバー

1. `enableEdgeToEdge`と既存のsystem bar inset処理は維持する。
2. navigationBarStyleのscrimを透明から不透明黒へ変更する。
3. 3ボタン方式とジェスチャー方式の両方で下端表示を実機確認する。
# 2026-06-22 ホームアクション行とsafeDrawing

1. Now Playing TopAppBarから誤配置した2つのモード切替を削除する。
2. `HomeTopAppBar`へ`SharedViewModel`を渡し、シークレット・ハイライト状態を購読する。
3. 通知・履歴・設定と同じactions行へ状態付きIconButtonを配置する。
4. ルートScaffoldを黒背景にし、`WindowInsets.safeDrawing`を一度だけ消費する。
5. ホーム小画面、Now Playing、下部ナビゲーション、3ボタン方式を実機確認する。
