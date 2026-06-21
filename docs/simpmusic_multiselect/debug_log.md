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

### 11. 2026-06-19 Forkへのpush認証失敗
- **事象**: `HTaE0213/core` へのpushがHTTP 403で拒否された。
- **原因**: 現在のGitHub認証ユーザーが `KHTaE1234` で、Fork所有者 `HTaE0213` と一致しない。
- **状態**: リモート設定は完了。`HTaE0213` での認証切り替え待ち。

### 12. 2026-06-19 コードレビュー軽微指摘
- **一括追加通知**: `failureMessage ?: successMessage` は失敗を優先するため、一部失敗で成功表示になる指摘は非該当。ただし成功/失敗件数が不明なため、各トラックの最終結果を集計して件数表示へ変更。
- **全選択スコープ**: モーダル内の明示的無効化2箇所以外で、`SongFullWidthItems` の全利用画面へ表示中の曲集合を `selectionScope` として補完。
- **シークレット除外ID**: 単純な上限削除は非公開再生曲を履歴へ再露出させるため不採用。DB列への移行または履歴削除連動の掃除が必要で、非阻断課題として維持。
- **検証**: 英日XML構文と `git diff --check` に加え、`:androidApp:assembleDebug --no-daemon -Pkotlin.incremental=false` が1分20秒で成功。
# 2026-06-19 実機再報告

- YouTubeプレイリストの並べ替えが本家相当の操作になっていない。
- 共同編集者の曲追加者アバターが表示されない。
- 曲名・アルバム名・アーティスト名が日本語表示から徐々に英語へ変化し、遅い。
- アルバム発売年が `null` と表示される。
- シークレット・サビメドレー切替が設定画面内で見つけにくい。
- 端末未接続のため、共同編集者の現行JSON応答はこのセッションでは未採取。
- 親・`core` の `git diff --check` 成功。英日 `strings.xml` のXML解析成功。
- APK初回再ビルドはPowerShellが `-Pkotlin.incremental=false` を `.incremental=false` タスクとして渡したため失敗。コードコンパイル前の引数エラー。引数配列方式へ変更。
- 引数修正後のビルドは `AlbumParser.kt:78` の `ResultAlbum.year` 非null型不一致で失敗。欠損時を空文字へ修正し、再ビルド対象。
- 次のビルドは `PlaylistScreen.kt:461/462/468/469` のドラッグ終了コールバック型推論で失敗。`Unit` コールバック型を明示して修正。
- 3回目のコードビルドは `BUILD SUCCESSFUL in 1m 16s`。全ABIデバッグAPK生成済み。実機挙動は未検証。
# 2026-06-20 レイアウト再修正検証

- `:androidApp:assembleDebug --no-daemon -Pkotlin.incremental=false`: `BUILD SUCCESSFUL in 1m 7s`。
- ADB端末 `3B1F4TE9ZUZ810Y7` へ `install -r` 成功。データを保持して起動成功。
- ホームと縦画面プレイリスト表示でクラッシュなし。
- ホーム推薦のプレイリストは所有フラグなしで開かれたため、所有時のみ表示される編集アイコンは未確認。
- Now Playingは新規再生による履歴変更を避けたため未確認。
# 2026-06-20 追加実機フィードバック

- 並べ替えモードで曲行のclickableが長押し入力を競合し、ドラッグ開始できない。
- 完了アイコンのcontentDescriptionが「編集」のまま。
- Now Playing下部ボタンがシステムナビゲーションバーと重なる。
- 修正差分はコードレビュー済み。検証待ち。
- 初回ビルドは `PlaylistScreen.kt:717/1071 Unresolved reference 'done'` で失敗。他エージェント報告と異なり生成リソースimportが不足していたため追加。
- import追加後は `BUILD SUCCESSFUL in 1m 6s`。ADB `install -r` 成功。
- Bug C: `シークレット [708,2113][753,2158]`、`サビメドレー [808,2113][853,2158]`。ナビゲーション領域より上に表示され、重なり解消を確認。
- Bug A/B: ライブラリから所有プレイリストを開く操作は通信エラーで読込できず、実機確認保留。クラッシュはなし。

# 2026-06-20 オンライン実機再検証

- ADB端末 `3B1F4TE9ZUZ810Y7` へ日本語リソース修正版を更新インストール。
- Bug A: 編集モード中の曲行タップで再生が始まらず、通常クリック競合の解消を確認。ドラッグ開始と保存はADBスワイプで再現できず手動確認待ち。
- Bug B: `done` の日本語定義不足により `Done` へフォールバック。`values-ja/strings.xml` へ `完了` を追加し、UIダンプの `content-desc="完了"` を確認。
- Bug C: 下部コントロール最下端Y=2195、ナビゲーションバー上端Y=2252で57pxの余白を確認。
- 対象操作後のlogcatでSimpMusicプロセスのFatal / Exceptionは検出されなかった。

# 2026-06-21 実機フィードバック

- ライブラリ経由では編集可能だが、ホーム経由で同じ所有プレイリストを開くと編集ボタンが表示されない。
- ドラッグハンドル操作が縦スクロールに奪われる場合がある。
- 並べ替え中の対象曲と挿入先の境界が視覚的に分かりにくい。
- 共同プレイリストの追加者アバターは引き続き表示されない。
- 曲名は原題ではなく英語表記が選択されている可能性がある。アルバム名は正常表示を確認。
- 修正後のAndroidデバッグビルドは `BUILD SUCCESSFUL in 1m 26s`。実機再確認待ち。
- ADB端末 `3B1F4TE9ZUZ810Y7` へ更新インストールし、ホームから「血便ちゃんねる」を開いて `content-desc="編集"` と `content-desc="完了"` を確認。
- 曲行右端に `content-desc="Mean Mr.Mustard"` の追加者アバター要素を複数確認。画面上でもアバター画像を目視確認。
- 編集モードで右端ハンドルの表示を確認。実データの順序を変更しないためドラッグ中の挿入ラインは未操作。
- PowerShellの予約変数 `$PID` と衝突して初回logcat抽出に失敗。`$appPid`へ変更して再実行し、アプリPIDのFatalなしを確認。

# 2026-06-21 連続並べ替えフィードバック

- 別位置までドラッグ後、指を離さず元位置へ戻しても最後の挿入候補が残り、no-opにならない。
- 移動API処理中は`playlistEditing`によりハンドルが無効になり、成功Toastまで次の操作ができない。
- 中断差分にはFIFOキューが追加されていたが、`Channel.send`を別コルーチンで行うため厳密な投入順保証がなく、原位置候補解除条件も到達不能だった。
- 原位置をドラッグ行中心で判定し、同期的`trySend`と保留件数管理へ修正。ビルド待ち。
- 修正後のAndroidデバッグビルドは `BUILD SUCCESSFUL in 1m 11s`。実ドラッグ確認待ち。
- ADB更新インストール・起動成功、Fatalなし。端末がロック中のためプレイリスト操作は未実施。

# 2026-06-21 編集完了導線フィードバック

- プレイリスト下方の曲を並べ替えた後、ヘッダー内の完了ボタンを押すため先頭まで戻る必要がある。
- 固定TopAppBarには検索操作しかなく、編集状態を終了する操作が欠落していることをコード上で確認。
- 編集中の固定TopAppBarへ完了アイコンを追加。`done`リソースimportと条件分岐を確認し、`git diff --check`成功。ビルド・実機確認は未実施。
# 2026-06-22 再生画面UIフィードバック

- シークレット・ハイライト切替が下部にあり、ヘッダーから操作できない。
- `MainActivity.enableEdgeToEdge`でstatusBarStyleのscrimが透明に設定されていることを確認。
- Android公式ページの取得は403で失敗。Android 15以降の見た目は実機確認対象とする。
- TopAppBarへ両トグルを移動し、下部の重複を削除。statusBarStyleを黒へ変更し、`git diff --check`成功。ビルド・実機確認は未実施。
## 2026-06-22 3ボタンナビゲーションバー

- `MainActivity.enableEdgeToEdge`のnavigationBarStyleだけ透明scrimのまま残っていた。
- 不透明黒scrimへ変更。Androidバージョンと3ボタン設定による見た目は実機確認待ち。
## 2026-06-22 配置先とシステムバー領域の訂正

- 添付画像から、対象ヘッダーはホーム画面の通知・履歴・設定アクション行と確定。
- 黒scrim指定だけではedge-to-edgeの内容領域がシステムバーの背後へ残り、表示が途切れて見えるとの実機報告を受理。
- ルートでsafeDrawingを消費し、アプリ内容をシステムバー内側へ制約する方針へ変更。
- HomeTopAppBarへの移動とルートsafeDrawingを実装。Now Playing側の状態購読・アイコン・リソースimportは削除。`git diff --check`成功、ビルド・実機確認待ち。
