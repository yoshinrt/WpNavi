# WpNavi - Waypoint Navi

[日本語はこちら](#wpnavi---waypoint-navi-1)

**WpNavi** is an app designed to complement Google Maps Navigation by supporting waypoint management for touring and driving.

Although Google Maps Navigation supports waypoints, it has several limitations:

- Navigation does not automatically resume to the next waypoint upon arrival.
- There is a limit on the number of waypoints you can add.
- Waypoints must be set on a smartphone, making it inconvenient to plan detailed routes on a PC.

WpNavi resolves these issues.

## How It Works

1. Create a KMZ file containing your route in advance (it is recommended to use [Google My Maps](https://www.google.com/maps/d/u/0/) on a PC).
1. WpNavi launches Google Maps Navigation to navigate to the first waypoint.
1. Upon arrival at the waypoint, WpNavi automatically starts navigation to the next waypoint. This repeats until you reach the final destination.

### Note: Regarding Root Privileges

- WpNavi requests root privileges in order to force-close the previous Google Maps Navigation instance and ensure the next navigation session launches reliably.
- WpNavi requests root privileges to automatically download and load the KMZ file when opening a Google My Maps URL.
- For technical details regarding actions that require root privileges, please search for `su` in the source code.

## How to Use

### 1. Creating a Route
Create a route with waypoints using [Google My Maps](https://www.google.com/maps/d/u/0/).

1. Click the "Add directions" button in My Maps to add a route layer.
1. Points (A), (B), etc., along the route will serve as WpNavi waypoints.
1. **When loading directly from My Maps**: After creating the route, set the sharing permissions to "Anyone with this link can view."
1. **When loading via KMZ file**: Click the three-dot menu icon next to the map title (top-left) $\rightarrow$ select "Export to KML/KMZ" to download the KMZ file and save it to your smartphone.

### 2. Opening a Route in WpNavi

- **Direct Download from Google My Maps**:
  - Tap the "My Maps (person icon)" at the top-right of the screen to open Google My Maps. Selecting the desired map will load the route into WpNavi.
  - *Note: The map sharing setting must be set to "Anyone with the link can view."*
- **Opening a KMZ File Saved on Your Smartphone**:
  - Tap the "Open File (floppy disk icon)" at the top-right of the screen to bring up the file picker, then select your saved KMZ file.

### 3. Starting Navigation
1. Use the "Previous" and "Next" buttons on the screen to select your starting waypoint.
1. Tap the "Start Navigation" button to launch Google Maps Navigation.
1. Each time you reach a waypoint, the app will automatically switch and launch navigation to the next destination.

---

# WpNavi - Waypoint Navi

**WpNavi** は，Google マップナビの経由地機能を補完し，ツーリングやドライブをサポートするアプリです．

Google マップナビにも経由地設定機能がありますが，以下のような課題があります．

- 経由地に到着しても，次の経由地へのナビが自動で開始されない
- 設定できる経由地の数に制限がある
- スマホ上での操作が必要で，PC 上で事前にじっくりルート設定できない

WpNavi は，これらの問題を解決します．

## 動作の仕組み

1. あらかじめルートを含んだ KMZ ファイルを作成します（作成作業は PC の [Google マイマップ](https://www.google.com/maps/d/u/0/) で行います）．
1. WpNavi が最初の経由地に向かう Google マップナビを起動します．
1. 経由地に到着すると，WpNavi が自動的に次の経由地への Google マップナビを起動します．これを最後の経由地に到着するまで繰り返します．

### 注意: root 権限について

- WpNavi は，次の Google マップナビの起動を確実にするため，直前の Google マップナビを強制終了する目的で root 権限を使用します．
- WpNavi は，Google マイマップの URL を開いた際に WpNavi 上で KMZ を自動ダウンロード・適用するために root 権限を使用します．
- root 権限を要求する具体的な処理内容については，ソースコード内で `su` を検索してご確認ください．

## 使用方法

### 1. ルートの作成
[Google マイマップ](https://www.google.com/maps/d/u/0/) で経由地を含んだルートを作成します．

1. マイマップの「ルートを追加」ボタンで，ルートを設定するレイヤーを追加します．
1. ルート上の各地点 (A)，(B) などが，WpNavi の経由地（ウェイポイント）になります．
1. **マイマップから直接読み込む場合**: 作成後，共有設定で「このリンクを知っている人なら誰でも表示できる」に設定します．
1. **KMZ ファイルとして読み込む場合**: 画面左上（地図タイトルの右）のメニュー（縦3点リーダー）から「KML/KMZ にエクスポート」を選び，KMZ 形式でスマホに保存します．

### 2. WpNavi でルートを開く

- **Google マイマップから直接ダウンロードする場合**:
  - 画面右上の「マイマップ（人型アイコン）」をタップすると Google マイマップが開きます．目的のマップを選択すると，WpNavi にルートが読み込まれます．
  - ※あらかじめマイマップ側で「リンクを知っている全員に公開」の設定が必要です．
- **スマホに保存した KMZ を開く場合**:
  - 画面右上の「ファイル開く（フロッピーディスクアイコン）」をタップしてファイル選択画面を開き，保存した KMZ ファイルを選択します．

### 3. ナビの開始
1. 画面上の「前」「次」ボタンで出発したい経由地を選択します．
1. 「ナビ開始」ボタンを押すと Google マップナビが起動します．
1. 経由地に接近・到着するたびに，自動的に次の経由地への案内へ切り替わります．
