package jp.dds.wpnavi

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Message
import android.preference.PreferenceManager
import android.util.Log
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import jp.dds.dds_lib.BuildConfig
import jp.dds.wpnavi.WpNaviService.WpNaviServiceLocalBinder
import org.osmdroid.config.Configuration as OsmConfiguration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File
import java.io.FileOutputStream

class WpNaviActivity : AppCompatActivity() {
	private var m_iCurWayPoint = 0
	private val m_WayPoint = KmlManager()
	private var m_Pref: SharedPreferences? = null
	private var m_strKmlFile: String? = null
	private var m_bDownloading = false
	private var m_bQuitService = false
	private var m_fZoom = 0.0
	private var m_fNosigZoom = 16.0

	private var m_MapView: MapView? = null
	private var m_LocationOverlay: MyLocationNewOverlay? = null
	private var m_RoutePolyline: Polyline? = null
	private val m_Markers = ArrayList<Marker>()

	// Android 標準ファイルピッカー (SAF) のランチャー
	private val kmlPickerLauncher = registerForActivityResult(
		ActivityResultContracts.OpenDocument()
	) { uri: Uri? ->
		uri?.let {
			val file = copyUriToCacheFile(it)
			if (file != null) {
				LoadKML(file.absolutePath, -1)
			} else {
				Toast.makeText(this, R.string.text_KMLNotLoaded, Toast.LENGTH_SHORT).show()
			}
		}
	}

	/*** Activity management ***/
	@Suppress("unused")
	@SuppressLint("InlinedApi")
	public override fun onCreate(savedInstanceState: Bundle?) {
		if (bDebug) Log.d("WpNavi", "WpNavi::onCreate")

		super.onCreate(savedInstanceState)

		// osmdroid の設定（HTTP ユーザーエージェントなどの登録）
		val ctx = applicationContext
		OsmConfiguration.getInstance().load(ctx, PreferenceManager.getDefaultSharedPreferences(ctx))
		OsmConfiguration.getInstance().userAgentValue = packageName

		// プリファレンス
		val pref = PreferenceManager.getDefaultSharedPreferences(this)
		m_Pref = pref

		// App Links 強制設定の確認と実行
		checkAndSetAppLinks()

		setContentView(R.layout.main)

		window.addFlags(
			WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
					WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
		)

		// ActionBar オーバーレイ設定
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
			val mActionBar = supportActionBar
			mActionBar?.setBackgroundDrawable(ColorDrawable(-0x80000000))
		}

		// 通知パーミッション確認
		checkNotificationPermission()

		DoIntent(intent)

		RegisterBroadcastReceiver()

		// 設定ロード
		m_iCurWayPoint = pref.getInt("key_waypoint", 0)
		m_strKmlFile = pref.getString("key_kml_file", null)
		m_fZoom = pref.getFloat("key_gmap_zoom", 1f).toDouble()
		m_fNosigZoom = pref.getFloat("key_nosig_zoom", 16f).toDouble()

		// 地図初期化
		SetupMap()
	}

	/**
	 * 隠し preference が未設定の場合に root 権限で App Links 設定を行い、設定済みにする
	 */
	private fun checkAndSetAppLinks() {
		val pref = m_Pref ?: return
		val isConfigured = pref.getBoolean("key_app_links_configured", false)

		if (!isConfigured) {
			val myPackage = packageName
			val domains = arrayOf("www.google.com")

			if (setAppLinksAsRoot(myPackage, *domains)) {
				if (bDebug) Log.d("WpNavi", "App Links root configuration succeeded.")
				pref.edit().putBoolean("key_app_links_configured", true).apply()
			} else {
				if (bDebug) Log.e("WpNavi", "App Links root configuration failed.")
			}
		}
	}

	/**
	 * root 権限 (su) で App Links のドメインリンク強制設定を実行する
	 */
	private fun setAppLinksAsRoot(packageName: String, vararg domains: String): Boolean {
		return try {
			val domainArgs = domains.joinToString(" ")
			val cmd = "pm set-app-links --package $packageName 1 $domainArgs"

			val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
			val exitCode = process.waitFor()

			exitCode == 0
		} catch (e: Exception) {
			if (bDebug) Log.e("WpNavi", "Error setting app links as root", e)
			false
		}
	}

	private fun checkNotificationPermission() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			if (ContextCompat.checkSelfPermission(
					this,
					Manifest.permission.POST_NOTIFICATIONS
				) != PackageManager.PERMISSION_GRANTED
			) {
				ActivityCompat.requestPermissions(
					this,
					arrayOf(Manifest.permission.POST_NOTIFICATIONS),
					REQUEST_NOTIFICATION_PERMISSION
				)
			}
		}
	}

	override fun onResume() {
		if (bDebug) Log.d("WpNavi", "WpNavi::onResume")
		super.onResume()
		m_MapView?.onResume()
		BindService()
	}

	override fun onPause() {
		if (bDebug) Log.d("WpNavi", "WpNavi::onPause")
		super.onPause()

		m_MapView?.onPause()

		// サービス停止
		val iStatus = if (mService != null) mService!!.GetStatus() else WpNaviService.STATUS_IDLE
		UnbindService()

		m_Pref?.edit()?.let { ed ->
			// GMap カメラ位置保存
			if (m_MapView != null) {
				val mapCenter = m_MapView!!.mapCenter

				if (iStatus != WpNaviService.STATUS_NOSIG) {
					m_fZoom = m_MapView!!.zoomLevelDouble
				}

				ed.putFloat("key_gmap_lng", mapCenter.longitude.toFloat())
				ed.putFloat("key_gmap_lat", mapCenter.latitude.toFloat())
				ed.putFloat("key_gmap_zoom", m_fZoom.toFloat())
				ed.putInt("key_waypoint", m_iCurWayPoint)
				ed.putString("key_kml_file", m_strKmlFile)
				ed.putFloat("key_nosig_zoom", m_fNosigZoom.toFloat())
			}

			ed.commit()
		}
	}

	fun onClickStartNavi(v: View?) {
		if (m_WayPoint.Size() == 0) {
			Toast.makeText(this, R.string.text_KMLNotLoaded, Toast.LENGTH_LONG).show()
			return
		}

		if (mService == null) return

		if (mService!!.GetStatus() == WpNaviService.STATUS_NOSIG) {
			mService!!.StopNavi()
		} else {
			StartService()
		}
	}

	fun onClickPrevWp(v: View?) {
		var iNewWp = m_iCurWayPoint - 1
		if (iNewWp < 0) iNewWp = m_WayPoint.Size() - 1
		SetMoveCurWayPoint(iNewWp)
	}

	fun onClickNextWp(v: View?) {
		var iNewWp = m_iCurWayPoint + 1
		if (iNewWp >= m_WayPoint.Size()) iNewWp = 0
		SetMoveCurWayPoint(iNewWp)
	}

	override fun onDestroy() {
		if (bDebug) Log.d("WpNavi", "WpNavi::onDestroy")

		StopService()

		UnregisterBroadcastReceiver()

		super.onDestroy()
	}

	// 画面回転時の destroy 防止
	override fun onConfigurationChanged(newConfig: Configuration) {
		super.onConfigurationChanged(newConfig)
		if (bDebug) Log.d("WpNavi", "WpNavi::onConfigurationChanged")
	}

	/*** OpenStreetMap (osmdroid) セットアップ ***/
	private fun SetupMap() {
		m_MapView = findViewById(R.id.map)
		m_MapView?.run {
			setTileSource(TileSourceFactory.MAPNIK)
			setMultiTouchControls(true)

			val pref = m_Pref
			if (pref != null) {
				val controller = controller
				controller.setZoom(m_fZoom)
				controller.setCenter(
					GeoPoint(
						pref.getFloat("key_gmap_lat", 0f).toDouble(),
						pref.getFloat("key_gmap_lng", 0f).toDouble()
					)
				)
			}
		}

		CheckLocationPermission()

		val tv = TypedValue()
		if (theme.resolveAttribute(android.R.attr.actionBarSize, tv, true)) {
			val topPadding =
				TypedValue.complexToDimensionPixelSize(tv.data, resources.displayMetrics)
			val bottomPadding = findViewById<View>(R.id.buttonPrevWp).height
			m_MapView?.setPadding(0, topPadding, 0, bottomPadding)
		}

		if (m_strKmlFile != null) LoadKML(m_strKmlFile, m_iCurWayPoint)
	}

	private fun CheckLocationPermission() {
		// 位置情報のパーミッション確認
		if (ContextCompat.checkSelfPermission(
				this,
				Manifest.permission.ACCESS_FINE_LOCATION
			) == PackageManager.PERMISSION_GRANTED
			|| ContextCompat.checkSelfPermission(
				this,
				Manifest.permission.ACCESS_COARSE_LOCATION
			) == PackageManager.PERMISSION_GRANTED
		) {
			EnableMyLocationOverlay()
		} else {
			ActivityCompat.requestPermissions(
				this,
				arrayOf(
					Manifest.permission.ACCESS_FINE_LOCATION,
					Manifest.permission.ACCESS_COARSE_LOCATION
				),
				REQUEST_LOCATION_PERMISSION
			)
		}
	}

	private fun EnableMyLocationOverlay() {
		val mapView = m_MapView ?: return
		m_LocationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), mapView)
		m_LocationOverlay?.enableMyLocation()
		mapView.overlays.add(m_LocationOverlay)
	}

	override fun onRequestPermissionsResult(
		requestCode: Int,
		permissions: Array<String>,
		grantResults: IntArray
	) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults)
		if (requestCode == REQUEST_LOCATION_PERMISSION) {
			if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
				EnableMyLocationOverlay()
			}
		} else if (requestCode == REQUEST_NOTIFICATION_PERMISSION) {
			if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
				if (bDebug) Log.d("WpNavi", "Notification permission granted")
			} else {
				if (bDebug) Log.d("WpNavi", "Notification permission denied")
			}
		}
	}

	fun SetCurWayPoint(iNewWp: Int) {
		if (m_Markers.isNotEmpty()) {
			m_Markers.getOrNull(m_iCurWayPoint)?.let { marker ->
				marker.icon = null
			}
			m_Markers.getOrNull(iNewWp)?.let { _ ->
				// 必要に応じて選択時のマーカー表示処理を追記
			}
		}
		m_iCurWayPoint = iNewWp
		m_MapView?.invalidate()
	}

	fun SetMoveCurWayPoint(iNewWp: Int) {
		if (m_Markers.isNotEmpty() && iNewWp < m_Markers.size) {
			SetCurWayPoint(iNewWp)
			val targetMarker = m_Markers[iNewWp]
			targetMarker.showInfoWindow()
			m_MapView?.controller?.animateTo(targetMarker.position)
		}
	}

	/*** Load KML ***/
	fun LoadKML(strKmlFile: String?, iWayPoint: Int): Boolean {
		val nextDist = m_Pref?.getInt("key_NextDistance", 50) ?: 50
		val Info = m_WayPoint.LoadKML(strKmlFile, nextDist)

		if (Info.m_iErrorCode != 0) {
			Toast.makeText(this, Info.m_iErrorCode, Toast.LENGTH_LONG).show()
			return false
		}

		val mapView = m_MapView ?: return false

		// 既存オーバーレイ削除
		m_Markers.forEach { mapView.overlays.remove(it) }
		m_Markers.clear()
		m_RoutePolyline?.let { mapView.overlays.remove(it) }

		m_iCurWayPoint = 0
		m_strKmlFile = strKmlFile

		// タイトル設定
		title = Info.m_strTitle ?: getString(R.string.app_name)

		// WP マーカーを Map に追加
		for (i in 0 until m_WayPoint.Size()) {
			val latLng = m_WayPoint.GetPoint(i)
			val marker = Marker(mapView)
			marker.setInfoWindowAnchor(0.5f, -1.0f)
			marker.position = GeoPoint(latLng.latitude, latLng.longitude)
			marker.title = String.format("WP%d", i + 1)
			marker.setOnMarkerClickListener { m, _ ->
				val wpIdx = m.title.substring(2).toInt() - 1
				SetMoveCurWayPoint(wpIdx)
				false
			}
			m_Markers.add(marker)
			mapView.overlays.add(marker)
		}

		val fDipScale = applicationContext.resources.displayMetrics.density

		// ポリラインを Map に追加
		val polyline = Polyline(mapView)
		polyline.outlinePaint.color = Color.rgb(0x11, 0x66, 0xFF)
		polyline.outlinePaint.strokeWidth = 6f * fDipScale

		// 吹き出しを表示しない設定
		polyline.infoWindow = null

		// タップイベントを無効化（イベントを消費して吹き出しを出さない）
		polyline.setOnClickListener { _, _, _ ->
			true // true を返すことでタップイベントを消費し、吹き出し処理をスキップ
		}

		val points = ArrayList<GeoPoint>()
		val polylineOptions = Info.m_Polyline
		if (polylineOptions != null && polylineOptions.points.isNotEmpty()) {
			for (pt in polylineOptions.points) {
				points.add(GeoPoint(pt.latitude, pt.longitude))
			}
		} else {
			for (i in 0 until m_WayPoint.Size()) {
				val pt = m_WayPoint.GetPoint(i)
				points.add(GeoPoint(pt.latitude, pt.longitude))
			}
		}

		polyline.setPoints(points)
		m_RoutePolyline = polyline
		mapView.overlays.add(polyline)
		val isReverse = m_Pref?.getBoolean("key_ReverseOrder", false) ?: false
		// 経度180度またぎの補正
		if (Info.m_dMaxLng - Info.m_dMinLng > 180) {
			val tmp = Info.m_dMaxLng
			Info.m_dMaxLng = Info.m_dMinLng
			Info.m_dMinLng = tmp
		}

		// ルート全体に移動
		val box = BoundingBox(Info.m_dMaxLat, Info.m_dMaxLng, Info.m_dMinLat, Info.m_dMinLng)

		mapView.post {
	
			// 上部アクションバーや下部ボタンを覆わないよう余白(80dp相当)を考慮して拡大
			val marginPx = (80f * fDipScale).toInt()
			mapView.zoomToBoundingBox(box, false, marginPx)
			mapView.invalidate()
			
			if (iWayPoint >= 0){
				SetMoveCurWayPoint(iWayPoint)
			}
		}

		mapView.invalidate()
		return true
	}
	
	/*** Option menu ***/
	override fun onCreateOptionsMenu(menu: Menu): Boolean {
		super.onCreateOptionsMenu(menu)
		menuInflater.inflate(R.menu.wp_navi, menu)
		return true
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean {
		val id = item.itemId

		if (id == R.id.itemLoadKML) {
			// KML / KMZ / XML / オクテットストリーム（汎用バイナリ）を許可
			kmlPickerLauncher.launch(
				arrayOf(
					"application/vnd.google-earth.kml+xml",
					"application/vnd.google-earth.kmz",
					"text/xml",
					"application/xml",
					"*/*"
				)
			)
			return true
		} else if (id == R.id.itemOpenGME) {
			val intent =
				Intent(Intent.ACTION_VIEW, Uri.parse(m_strGMEUrl + "/?authuser=0&action=open"))
			intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
			startActivity(intent)
			return true
		} else if (id == R.id.itemSetting) {
			val intent = Intent(this@WpNaviActivity, WpNaviPreference::class.java)
			startActivityForResult(intent, 0)
			return true
		}

		return super.onOptionsItemSelected(item)
	}

	/**
	 * ピッカーで選択された Uri から一時ファイルを生成し File オブジェクトを取得するヘルパー関数
	 */
	private fun copyUriToCacheFile(uri: Uri): File? {
		return try {
			val inputStream = contentResolver.openInputStream(uri) ?: return null
			val outputFile = File(cacheDir, "selected_route.kml")
			FileOutputStream(outputFile).use { output ->
				inputStream.use { input ->
					input.copyTo(output)
				}
			}
			outputFile
		} catch (e: Exception) {
			if (bDebug) Log.e("WpNavi", "Failed to copy file from Uri", e)
			null
		}
	}

	/*** GME URL intent ***/
	override fun onNewIntent(intent: Intent) {
		if (bDebug) Log.d("WpNavi", "WpNavi::onNewIntent")
		super.onNewIntent(intent)
		DoIntent(intent)
	}

	fun DoIntent(intent: Intent?): Boolean {
		if (intent == null) return false
		if (bDebug) Log.d("WpNavi", "DoIntent:Action:" + intent.action)

		if (intent.getBooleanExtra("quit_service", false)) {
			if (bDebug) Log.d("WpNavi", "DoIntent:Killed by notification")
			m_bQuitService = true
			return true
		}

		// URL フィルタに引っかかった
		val strUrl = intent.dataString ?: return false
		return DownloadURL(strUrl)
	}

	fun DownloadURL(strUrl: String): Boolean {
		val strDstFile =
			getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS).toString() + m_strDownloadKmlNameTmp

		try {
			File(strDstFile).delete()
		} catch (e: Exception) {
		}

		if (bDebug) Log.d(
			"WpNavi",
			"WpNavi::DoIntent:editUrl:$strUrl"
		)

		val strMid = strUrl.replaceFirst(".*mid=".toRegex(), "").replaceFirst("&.*".toRegex(), "")
		if (bDebug) Log.d(
			"WpNavi",
			"WpNavi::DoIntent:editUrl:$strMid"
		)

		val uriBuilder = Uri.parse(m_strGMEUrl + "/kml").buildUpon()
		uriBuilder.appendQueryParameter("authuser", "0")
		uriBuilder.appendQueryParameter("mid", strMid)

		if (bDebug) Log.d(
			"WpNavi",
			"WpNavi::DoIntent:kmlUrl:$uriBuilder"
		)

		val request = DownloadManager.Request(uriBuilder.build())
		request.setDestinationInExternalFilesDir(
			this@WpNaviActivity,
			Environment.DIRECTORY_DOWNLOADS,
			m_strDownloadKmlNameTmp
		)
		request.setVisibleInDownloadsUi(false)
		request.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_MOBILE or DownloadManager.Request.NETWORK_WIFI)

		m_bDownloading = true
		(getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)

		return true
	}

	var mReceiver: BroadcastReceiver? = object : BroadcastReceiver() {
		override fun onReceive(context: Context, intent: Intent) {
			val action = intent.action
			if (DownloadManager.ACTION_DOWNLOAD_COMPLETE == action) {
				val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)

				val query = DownloadManager.Query()
				query.setFilterById(id)
				val cursor = (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).query(query)

				if (cursor.moveToFirst() && m_bDownloading) {
					val statusIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
					val status = cursor.getInt(statusIndex)

					if (status == DownloadManager.STATUS_SUCCESSFUL) {
						m_bDownloading = false

						val strTmpFile =
							getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS).toString() + m_strDownloadKmlNameTmp
						val strKmlFile =
							getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS).toString() + m_strDownloadKmlName

						val fileKml = File(strKmlFile)
						try {
							fileKml.delete()
						} catch (e: Exception) {
						}
						try {
							File(strTmpFile).renameTo(fileKml)
						} catch (e: Exception) {
						}
						LoadKML(strKmlFile, -1)
					} else {
						Toast.makeText(
							this@WpNaviActivity,
							R.string.text_DownloadFailed,
							Toast.LENGTH_LONG
						).show()
					}
				}
				cursor.close()
			}
		}
	}

	fun RegisterBroadcastReceiver() {
		val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			registerReceiver(mReceiver, filter, RECEIVER_EXPORTED)
		} else {
			registerReceiver(mReceiver, filter)
		}
	}

	fun UnregisterBroadcastReceiver() {
		if (mReceiver != null) unregisterReceiver(mReceiver)
		mReceiver = null
	}

	/*** Service ***/
	private var mService: WpNaviService? = null

	private val mConnection: ServiceConnection = object : ServiceConnection {
		override fun onServiceConnected(className: ComponentName, service: IBinder) {
			if (bDebug) Log.d("WpNavi", "WpNavi::onServiceConnected")

			mService = (service as WpNaviServiceLocalBinder).service

			if (mService!!.GetStatus() != WpNaviService.STATUS_IDLE) {
				SetCurWayPoint(mService!!.iCurWayPoint)
			}

			mService!!.m_MsgHandler = object : Handler() {
				override fun handleMessage(Msg: Message) {
					if (mService != null) when (Msg.what) {
						WpNaviService.MSG_UPDATE_WP -> {
							SetCurWayPoint(mService!!.iCurWayPoint)
							OnLocationChanged(mService!!.m_Location!!)
						}

						WpNaviService.MSG_UPDATE -> OnLocationChanged(mService!!.m_Location!!)
						WpNaviService.MSG_CHG_STATE -> OnStateChanged(Msg.arg1)
					}
				}
			}

			val iStatus = mService!!.GetStatus()
			if (m_bQuitService || iStatus == WpNaviService.STATUS_RUNNING) {
				mService!!.StopNavi()
				if (bDebug) Log.d(
					"WpNavi",
					"Service stopped:$iStatus"
				)
				m_bQuitService = false
			} else if (iStatus == WpNaviService.STATUS_NOSIG) {
				EnterNosigUI()
			}

			if (bDebug) Log.d(
				"WpNavi",
				"Service's stat=$iStatus WP=$m_iCurWayPoint"
			)
		}

		override fun onServiceDisconnected(className: ComponentName) {
			if (bDebug) Log.d("WpNavi", "WpNavi::onServiceDisconnected")
			mService = null
		}
	}

	fun StartService() {
		val intent = Intent(this, WpNaviService::class.java)

		val pref = m_Pref
		intent.putExtra("WayPoint", m_WayPoint.Points)
		mService!!.iCurWayPoint = m_iCurWayPoint
		mService!!.iNextDistance = pref?.getInt("key_NextDistance", 50) ?: 50
		mService!!.iWaitTime = (pref?.getInt("key_WaitTime", 20) ?: 20) * 100
		mService!!.bKillByRoot = pref?.getBoolean("key_kill_by_root", true) ?: true
		mService!!.bReverseOrder = pref?.getBoolean("key_ReverseOrder", false) ?: false
		mService!!.bRestartTest = pref?.getBoolean("key_kill_test", false) ?: false

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			ContextCompat.startForegroundService(this, intent)
		} else {
			startService(intent)
		}
	}

	fun StopService() {
		stopService(Intent(this, WpNaviService::class.java))
	}

	fun BindService() {
		bindService(Intent(this, WpNaviService::class.java), mConnection, BIND_AUTO_CREATE)
	}

	fun UnbindService() {
		if (mService != null) {
			unbindService(mConnection)
			mService = null
		}
	}

	/*** 無電波モード ***/
	fun EnterNosigUI() {
		window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

		val btn = findViewById<Button>(R.id.buttonStartNavi)
		btn.setText(R.string.button_stop_navi)

		m_MapView?.run {
			m_fZoom = zoomLevelDouble
			controller.setZoom(m_fNosigZoom)
		}

		if (bDebug) Log.d(
			"WpNavi",
			"EnterNosigUI::z:$m_fZoom nz:$m_fNosigZoom"
		)
	}

	fun ExitNosigUI() {
		window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

		m_MapView?.controller?.setZoom(m_fZoom)

		if (bDebug) Log.d(
			"WpNavi",
			"ExitNosigUI::z:$m_fZoom nz:$m_fNosigZoom"
		)

		val btn = findViewById<Button>(R.id.buttonStartNavi)
		btn.setText(R.string.button_start_navi)
	}

	fun OnLocationChanged(location: Location) {
		m_MapView?.run {
			val geoPoint = GeoPoint(location.latitude, location.longitude)
			m_fNosigZoom = zoomLevelDouble
			controller.animateTo(geoPoint)
			mapOrientation = -location.bearing
		}
	}

	// サービスステート変更
	fun OnStateChanged(iPrevState: Int) {
		if (mService == null) return

		if (mService!!.GetStatus() == WpNaviService.STATUS_NOSIG) {
			EnterNosigUI()
		} else if (iPrevState == WpNaviService.STATUS_NOSIG) {
			ExitNosigUI()
		}
	}

	companion object {
		val bDebug: Boolean = BuildConfig.DEBUG
		private const val m_strGMEUrl = "https://www.google.com/maps/d"
		private const val m_strDownloadKmlName = "/wpnavi.kml"
		private const val m_strDownloadKmlNameTmp = "/wpnavi.kml.tmp"
		private const val REQUEST_LOCATION_PERMISSION = 1001
		private const val REQUEST_NOTIFICATION_PERMISSION = 1002
	}
}
