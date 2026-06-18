# デバッグログ (Debug Log)

## バグ履歴

### 1. エミュレータ再起動後のインストールエラー
- **事象**: `./gradlew :androidApp:installDebug` 実行時に `INSTALL_FAILED_INSUFFICIENT_STORAGE` エラーが発生し、インストールが失敗する。
- **原因**: 以前のセッションでインストールされたデバッグ版アプリパッケージと、新たに起動し直したエミュレータ上のメタデータやストレージ状態が競合していたため。
- **解決策**: `adb uninstall com.maxrave.simpmusic.dev` を実行して明示的に古いパッケージをアンインストールしてから再インストールを実行することで、正常に完了することを確認。

### 2. SearchViewModelのJVMシグネチャ衝突
- **事象**: 2026-06-18の `:androidApp:assembleDebug` が `:composeApp:compileAndroidMain` で失敗。
- **原因**: `ArtistsResult` 用と `Artist` 用の2つの `mergeArtists(List, List)` が、JVM型消去後に同じシグネチャとなる。
- **解決策**: `Artist` 用を `mergeTrackArtists`、`ArtistsResult` 用を `mergeArtistResults` へ改名。
- **検証**: `:androidApp:assembleDebug` を再実行し、2026-06-18に `BUILD SUCCESSFUL`（41秒）を確認。

### 3. 実機検証前コードレビュー
- **一括プレイリスト追加**: `firstOrNull()` が最初の `LocalResource.Loading` でFlowを中断するため、DB追加処理へ到達しない。
- **選択モード**: 共通行コンポーネントの全利用画面で長押しが有効だが、操作バーは検索・プレイリスト・ローカルプレイリストにしかない。
- **日英マージ**: 英語FlowがLoading中でも日本語SuccessをUIへ渡すため、表示切り替えが発生し得る。
- **言語判定**: カタカナを含み、ひらがな・漢字がない邦楽タイトルも翻訳名と誤判定する。
- **先読み**: `CacheWriter` がプリキャッシュ親JobではなくサービスScopeで起動され、曲変更時にキャンセルされない。標準HTTP DataSource使用により設定済みプロキシも経由しない。
- **検証状態**: コードレビューのみ。修正および実機検証は未実施。

### 4. レビュー修正後のビルドエラー
- **事象**: `:androidApp:assembleDebug` が `SettingScreen.kt` の文字列フォーマット引数型不一致で失敗。
- **原因**: `highlightDuration` が nullable `Int?` のまま `stringResource` へ渡されていた。
- **対応**: null時に20秒へ正規化する `selectedHighlightDuration` を導入。
- **検証**: `:androidApp:assembleDebug` が2026-06-18に `BUILD SUCCESSFUL`（最終実行20秒）。

### 5. レビュー指摘の再検証
- **日英combine**: `Resource` はSuccess/Errorのみで、両Flowの初回発行前にcombineは実行されないため、Loading中の早期表示という指摘は非該当。
- **差分検査**: 親リポジトリとcoreサブモジュールの `git diff --check` が成功。
- **実機状態**: 未検証。GUI・Android Auto・実ネットワーク挙動は人間確認待ち。

### 6. 2026-06-18 実機フィードバック
- 検索結果で複数選択を開始できない。要件は曲・動画行の全画面対応。
- 選択中の戻る操作が選択解除ではなく画面遷移になる。
- メタデータは日本語表示後に段階的に変化し、Queen「地獄へ道連れ」など未解決項目もある。
- ミニプレイヤーは更新されるが通知は元の日本語表記のまま。
- 再生開始が本家YouTube Musicより遅い。
- タスク終了後のアプリ再起動でキュー・現在曲・再生位置が復元されない。

### 7. 2026-06-18 グローバル複数選択の静的検査
- 親リポジトリと `core` サブモジュールの `git diff --check` は成功。
- Compose 1.11.1の解決済みメタデータに `org.jetbrains.compose.ui:ui-backhandler` が含まれることを確認。
- 狭幅画面での操作ボタン幅超過を防ぐため、操作バーを2段構成に修正。
- キューモーダル内の共通曲行は、操作バーが背面に隠れるため複数選択を明示的に無効化。
- JetBrains診断MCPが利用できず、プロジェクト指示に従いGradleビルドは未実施。コンパイル状態は未検証。

### 8. 2026-06-19 実機フィードバック
- **クラッシュ**: `SharedViewModel.addSelectedToYouTubePlaylist` が `Dispatchers.IO` 上で `makeToast()` を呼び、Android 16で `Can't toast on a thread that has not called Looper.prepare()` が発生。
- **戻る操作**: 選択解除されず前画面へ遷移。
- **プレイリストUI**: 追加シートの初期表示がローカルプレイリストに限定され、YouTube側へ切り替える手間がある。
- **メドレー**: 通常冒頭が一瞬再生された後にハイライト位置へシークする。
- **通知**: 発生操作不明の `null` スナックバーが定期的に表示される。

### 9. 2026-06-19 共同プレイリスト・シークレット実装
- **API調査**: WebSearchは403。代替としてOSS `ytmusicapi` の `2025_10_get_playlist_collaborative.json` を確認し、`contributorsAvatars.avatarStackViewModel` に追加者名・画像URL・チャンネルIDがあることを特定。
- **履歴漏れ**: 新規 `SongEntity` 挿入時に `inLibrary = now()` となるため、書き込み更新を止めるだけでは最近再生に残る。
- **対応**: 復元用の曲保存は維持し、シークレット中に新規保存した曲IDをDataStoreに記録して最近項目から除外。通常再生時に除外を解除。
- **検証**: 英日XML構文は正常。親・`core` の `git diff --check` は成功。JetBrains診断MCPがないためコンパイルと実機動作は未検証。

### 10. 2026-06-19 APKビルド初回失敗
- **事象**: `:androidApp:assembleDebug` が `:domain:compileAndroidMain` で失敗。
- **原因**: 最初のコマンドがツールタイムアウトで中断された後もKotlin Daemonが残り、増分キャッシュのストレージが二重登録された。コード診断ではなくビルド環境キャッシュの競合。
- **対応**: Gradle Daemonを停止し、増分コンパイルを無効化して再実行する。
- **再試行1**: PowerShellが `-Pkotlin.incremental=false` を `.incremental=false` タスクとして渡したため、ビルド開始前に失敗。引数配列で再実行する。
- **最終結果**: 引数配列で `:androidApp:assembleDebug --no-daemon -Pkotlin.incremental=false` を実行し、1分43秒で `BUILD SUCCESSFUL`。コンパイルエラーなし。
