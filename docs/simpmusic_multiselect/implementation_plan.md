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
