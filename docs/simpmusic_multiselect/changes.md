# 変更履歴 (Changes)

## セッション履歴
- 新規プロジェクト初期化
- **2026-06-14 (現在セッション)**:
  - **再生遅延対策のビルドエラー修正**: `Media3ServiceModule.kt` において `com.maxrave.domain.extension` から `isBefore` と `plusSeconds` のインポートを追加し、`MERGING_DATA_TYPE` のインポートを復元した。これにより `:media3` のコンパイルが成功。
  - **オリジナル言語表示のビルドエラー修正**: `composeApp` に `:kotlinYtmusicScraper` 依存を持たせずに `YouTube` クライアントを利用するため、`MetadataLanguageHelper` に `KoinComponent` を実装して `YouTube` をデリゲート注入。`FullWidthItems.kt` での `YouTube` 参照を排除し、`:androidApp:assembleDebug` の完全ビルド成功を確認。
  - **並列マージ法（オプションD）とボトムバー被り問題の修正（セッション後半）**:
    - `App.kt` にて、複数選択モード中はボトムバー（ナビゲーション＆ミニプレイヤー）を非表示にする条件を追加。
    - `YouTube.kt` (`customQuery`)、`SearchRepository`、`PlaylistRepository`、および実装クラスのシグネチャを拡張し、`hl` / `gl` の String パラメータを渡せるように実装。これにより `core:domain` レイヤーから Scraper モジュールへの直接依存を排除。
    - `SearchViewModel` と `PlaylistViewModel` にて、Coroutines の `combine` を用いて日本語・英語のAPIリクエストを並列実行し、カタカナ翻訳を英語でマージするロジックを実装。
    - `FullWidthItems.kt` の個別非同期解決 `LaunchedEffect` を無効化し、表示後の個別解決によるカクつきと無駄なAPIリクエストを防止。
    - `composeApp` モジュールが Scraper モジュールに依存していないことによる `YouTubeLocale` 参照コンパイルエラーを、String パラメータへの置き換えで解決。
    - `Resource` クラスに存在しない `Resource.Loading()` を呼び出していた箇所を `jaRes` 返却に修正し、コンパイルを通した。
- **2026-06-18 状態監査**:
  - 現在ブランチが `feature/multiselect-langfix`、基点が `c4c4ddb` (`v1.4.0`) であることを確認。
  - 親リポジトリに追跡済み14ファイルの差分（922行追加、332行削除）と未追跡の `MultiSelectBottomBar.kt` があることを確認。
  - `core` サブモジュールに追跡済み18ファイルの差分（602行追加、117行削除）と未追跡ディレクトリがあることを確認。
  - `git diff --check` で `FullWidthItems.kt` の行末空白を3件検出。未修正。
  - 実装タスクと手動検証タスクを分離し、フェーズを「検証・確認」に同期。
  - `SearchViewModel.kt` の型消去によるJVMシグネチャ衝突を、用途別の関数名への変更で解消。
  - `:androidApp:assembleDebug` が成功し、全ABIのデバッグAPKを2026-06-18 19:19に生成。
  - 実機検証前コードレビューを実施。コード変更は行わず、機能ブロッカー5件と追加のUI・品質課題を記録。
  - 一括追加Flowを最後まで収集するよう修正し、処理結果をまとめて通知。
  - 複数選択を対象3画面でのみ有効化し、すべて選択操作を追加。
  - メタデータ英語解決をタイトル・アーティスト双方がカタカナ候補の場合に限定。
  - 重複する手動CacheWriter先読みを削除し、既存ExoPlayerプリキャッシュへ一本化。
  - Android Autoコールバックの手動ロードを削除し、返却キューとの二重設定を解消。
  - 歌詞切替を歌詞取得済みの現在ページに限定。
  - ハイライト設定文言を英語・日本語リソースへ移行。
  - `:androidApp:assembleDebug` 最終成功、全ABI APKを2026-06-18 19:50に生成。
  - 共通曲行の複数選択を既定で有効化し、選択範囲を `SharedViewModel` に保持する構成へ変更。
  - `MultiSelectBottomBar` を `App.kt` のグローバルボトムバーへ移動し、各画面の重複配置を削除。
  - 複数選択中の戻る解除、一括「次に再生」、YouTubeプレイリスト一括追加を実装。現時点で未検証。
  - `BaseViewModel.makeToast` を `viewModelScope` 経由に変更し、IOスレッドクラッシュと空/null通知を抑止。
  - `BackHandler` をNavigationより後に合成される位置へ移動。
  - 複数選択のプレイリスト追加シートはYouTubeプレイリストを初期表示。
  - 再生画面にメドレートグルを追加し、Androidでハイライト解決中は一時停止してシーク後に再開するよう変更。
  - `PlaylistRepository` にYouTubeプレイリスト曲削除・移動操作を追加。`PlaylistScreen` で所有プレイリストの削除アクションと上下移動編集モードを提供。
  - シークレットモードの要件を「再生位置のみ保持し、履歴・再生回数・ランキング・分析イベントを記録しない」と確定。
  - 本家YT Musicの共同プレイリストで、曲行右端のアバターが追加者表示であることをユーザー提供画像で確認。
  - `contributorsAvatars` 応答モデル、追加者ドメインモデル、初回・継続パーサー、共通曲行のアバター表示を実装。
  - シークレット設定を追加し、検索履歴・最近再生・再生回数・ローカル分析・YouTube再生トラッキングをAndroid/JVMで抑止。キューと再生位置は保持。
  - シークレット中の新規曲を復元用DBに保持しつつ最近項目から除外するID管理をDataStoreに追加。
  - ユーザーの明示依頼を受け、最新変更を含むデバッグAPK生成を開始。
  - Kotlin Daemonの増分キャッシュ競合をDaemon停止・増分無効化で回避し、全ABIデバッグAPK 4件を正常生成。SHA-256を確認。
  - `core` のdetached HEADを `codex/simpmusic-enhancements-core` へ移し、実装一式を `be35e8e` としてコミット。
  - 親を `codex/simpmusic-enhancements` へ移し、アプリUIと `core` ポインタを `e471134` としてコミット。
  - `.agents`、`.codex`、`AGENTS.md`、他トピック文書、ログ、画像はコミット対象外として保持。
  - Fork先として `HTaE0213/core` と `HTaE0213/SimpMusic` を受領し、リモート設定とpushを開始。
  - 親・`core` とも `origin` をHTaE0213のFork、`upstream` をmaxrave-dev本家へ変更。
  - GitHub CLIのアクティブアカウントを `KHTaE1234` から `HTaE0213` へ切り替え、`core` ブランチをpush。
  - 親 `codex/simpmusic-enhancements` を `HTaE0213/SimpMusic` へpushし、両ブランチのupstream trackingを設定。
  - コードレビュー所見を受領。一括追加通知粒度と全選択スコープ不足を有効な軽微指摘として修正開始。除外IDの単純上限削除はシークレット履歴再露出につながるため不採用。
  - ローカル/YouTubeプレイリスト一括追加で、トラックごとの最終結果を集計し「成功X件・失敗Y件」を表示。
  - アーティスト、アルバム、動的プレイリスト、最近再生、ライブラリ、分析画面の共通曲行へ `selectionScope` を追加。キューモーダルは複数選択無効を維持。
  - タスク31〜40を実装完了へ整理し、実機検証をタスク45〜49へ分離。
  - レビュー修正を `b29ea7f` としてForkへpush。Androidデバッグビルド成功後、全ABI APKを2026-06-19 21:56に更新。
# 2026-06-19 実機フィードバック再修正

- アプリUI言語とYouTubeメタデータ取得言語を分離し、取得側を英語へ固定。
- アルバム発売年を字幕ランから4桁で抽出し、欠損値を文字列 `null` に変換しないよう修正。
- 共同編集者アバターの応答名として `facepile` を互換受理。
- YouTubeプレイリストの上下矢印を長押しドラッグへ変更。確定時のみ既存移動APIを呼ぶ。
- 再生画面下部にシークレット・サビメドレーの状態付きラベルボタンを追加。
- 曲行ごとの英語メタデータ追加取得を削除し、取得済みメタデータの即時表示へ統一。
- 未検証。ロールバックは本節に対応する `core` 差分を戻す。
- ビルド証跡: `:androidApp:assembleDebug` 成功。arm64-v8a APKは61.77 MB、SHA-256 `B7D5ACE2502BE2AED545EA90F074FDF70EA0644B9D69139EE29E248B4B956F0D`。
- `core` コミット: `c4d5c1f` (`fix(metadata): Load original names and album years`)。Forkへpush済み。
- 親コミット: `8bee716` (`feat(player): Improve playlist editing and mode controls`)。Forkへpush済み。
# 2026-06-20 実機レイアウト再修正

- 縦画面の所有YouTubeプレイリストで、検索とメニューの間に編集/完了トグルを追加。
- Now Playing下部のシークレット・サビメドレーをラベル付きボタンから32dp状態付きアイコンへ変更。
- 未検証。ロールバックは本節に対応する親リポジトリ差分を戻す。
- ビルド証跡: `BUILD SUCCESSFUL in 1m 7s`。arm64-v8a APKは62.21 MB、SHA-256 `BF5EFEC1691B9F9E2318FE9D62DD5AEA4E44D753B524ACBBD27D41C7B09F3108`。
- ADB更新インストールと起動に成功。アプリPIDの致命的エラーなし。
- 親コミット: `d25777f` (`fix(ui): Keep playlist and player actions visible`)。Forkへpush済み。
# 2026-06-20 実機デバッグ追加修正

- ドラッグハンドル表示中は曲行のclickableを外し、親LazyColumnへ長押しドラッグを渡す。
- 編集/完了アイコンのcontentDescriptionを状態に応じて切り替える。
- Now Playing下部アクション後へシステムナビゲーションバー高さの余白を追加。
- 他エージェント差分をレビュー済み。ビルド・実機検証前。
- `done` の生成リソースimport不足を追加修正。
- ビルド証跡: `BUILD SUCCESSFUL in 1m 6s`。arm64-v8a APKは61.77 MB、SHA-256 `CB2F64E7C3BE6FCBC1E0B94C7BA3ED5217E53C2B34164056A8A61644C8960699`。
- ADB更新インストール成功。Now Playingの両モードボタンはシステムナビゲーション領域より上に表示。
- 親コミット: `74618ca` (`fix(ui): Restore playlist drag and bottom controls`)。Forkへpush済み。

# 2026-06-20 実機オンライン検証＆リソース修正

- 所有YouTubeプレイリストの編集画面において、日本語ロケール下で「完了」アイコンの `contentDescription` が定義されておらず英語の `Done` にフォールバックする不具合を特定。
- `composeApp/src/commonMain/composeResources/values-ja/strings.xml` に `<string name="done">完了</string>` を追加。
- ビルド・インストールし、日本語で「完了」と正しく表示・読み上げられることを実機ダンプで確認（Bug Bの解決）。
- ビルド証跡: `BUILD SUCCESSFUL in 59s`。arm64-v8a APKは61.77 MB、SHA-256 `0A4B327A1042BA4087D60EED5E8C98C2240E3B1566686366A3CA199616CDD61C`。
- 編集モード中に曲行をタップした際の誤動作（再生開始）が発生しないようクリックイベントが無効化されていることを確認（Bug Aのクリック競合解消）。
- Now Playing画面の下部コントロールボタン群が、システムバーインセット適用によりナビゲーションバー（Y=2252〜2362）と被らず、最下端 Y=2195 で表示されていることをbounds情報から確認（重なり解消の再確認）。
- 対象操作後のlogcatを確認し、SimpMusicプロセスのFatal / Exceptionが検出されないことを確認。
- 親コミット: `2a6ebba` (`fix(i18n): Localize playlist completion action`)。Forkへpush済み。

# 2026-06-21 プレイリスト編集UX・追加者・原題再修正

- 編集可能ヘッダーをドメイン状態へ追加し、ホーム経由でも所有プレイリストの編集操作を表示可能にした。
- 並べ替えジェスチャをリスト全体の長押しから、右端48dpハンドルの即時ドラッグへ変更した。
- 移動行の背景・枠と、挿入候補境界の3dpラインを追加した。
- 初期プレイリストパーサーで `contributorsAvatars` を `Track.addedBy` へ渡すよう修正した。
- YouTubeクライアントの英語固定を解除し、端末言語応答と明示的英語応答のマージを復元した。
- 親・`core`の `git diff --check` と英日XML解析に成功。
- ビルド証跡: `BUILD SUCCESSFUL in 1m 26s`。arm64-v8a APKは61.86 MB、SHA-256 `36EBF15A0A1F4ABFE960BC57FFEEFF3FB070125615E53E60CB1547C727080651`。
- ADB更新インストール成功。ホーム経由の所有プレイリストで編集ボタンと完了切替を確認。
- 初期表示曲の右端に追加者アバターと追加者名のcontentDescriptionが表示されることを確認。
- 邦楽タイトルの日本語表示と対象操作後のFatalなしを確認。ドラッグ中表示と全カタログの原題判定は人間確認待ち。
- `core`コミット: `944127c` (`fix(playlist): Preserve edit and contributor metadata`)。Forkへpush済み。
- `core`コミット: `255b7fd` (`fix(metadata): Restore the selected YouTube locale`)。Forkへpush済み。
- 親コミット: `366706c` (`fix(playlist): Improve editing entry and drag feedback`)。Forkへpush済み。

# 2026-06-21 連続並べ替え再修正

- ドラッグ行の中心が元の行領域へ戻った時点で挿入候補を解除するよう変更。
- API通信中のハンドル無効化を削除し、ローカル順序を即時反映するよう変更。
- 移動操作を同期的にFIFOキューへ投入し、単一consumerで直列送信するよう変更。
- 成功Toastをキューの保留件数が0になった時の1回へ集約。
- API失敗後の後続index操作を停止し、サーバー順序の再取得で整合させるよう変更。
- 親・`core`の `git diff --check` に成功。
- ビルド証跡: `BUILD SUCCESSFUL in 1m 11s`。arm64-v8a APKは62.31 MB、SHA-256 `C0C2BD7212E0F8436AC0BCB0F8FE0B8D63808CA7EDA90F667749809D422450B5`。
- 実ドラッグ確認は未検証。
- ADB更新インストールと起動に成功。アプリPIDのFatalなし。端末ロックのため実ドラッグ検証は引き継ぎ。
- 親コミット: `3f88bde` (`fix(playlist): Queue consecutive reorder operations`)。Forkへpush済み。
# 2026-06-21 固定編集完了操作

- 下方の曲を並べ替えた後、先頭へ戻らないと編集を完了できない実機フィードバックを受理。
- 折り畳み後の固定TopAppBarで、編集時は検索の代わりに完了アイコンを表示するよう変更。
- `git diff --check`成功。Gradleビルドと実機確認は未実施。
- 親コミット: `9d26bc7` (`fix(playlist): Keep edit completion accessible`)。Forkへpush済み。
# 2026-06-22 Now Playingヘッダーとステータスバー

- シークレットモードとハイライト機能の状態付き切替をNow Playing TopAppBarへ移動。
- 再生画面下部から同じ2つの切替を削除。
- AndroidのstatusBarStyle scrimを透明から不透明黒へ変更。
- `git diff --check`成功。Gradleビルドと実機確認は未実施。
- 親コミット: `531a2e2` (`fix(player): Move mode toggles into header`)。Forkへpush済み。
