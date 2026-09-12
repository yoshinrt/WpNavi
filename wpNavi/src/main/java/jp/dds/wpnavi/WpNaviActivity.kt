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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import jp.dds.dds_lib.BuildConfig
import jp.dds.dds_lib.FileOpenDialog
import jp.dds.dds_lib.FileOpenDialog.FileOpenDialogListener
import jp.dds.wpnavi.WpNaviService.WpNaviServiceLocalBinder
import java.io.File

class WpNaviActivity : AppCompatActivity(), FileOpenDialogListener, OnMapReadyCallback {
	private var m_iCurWayPoint = 0
	private val m_WayPoint = KmlManager()
	private var m_Pref: SharedPreferences? = null
	private var m_strKmlFile: String? = null
	private var m_bDownloading = false
	private var m_bQuitService = false
	private var m_fZoom = 0f
	private var m_fNosigZoom = 0f

	private var m_Map: GoogleMap? = null
	private val m_Markers = ArrayList<Marker>()

	/*** Activity management  */
	@Suppress("unused")
	@SuppressLint("InlinedApi")
	public override fun onCreate(savedInstanceState: Bundle?) {
		if (bDebug) Log.d("WpNavi", "WpNavi::onCreate")

		super.onCreate(savedInstanceState)

		// プリファレンス
		val pref = PreferenceManager.getDefaultSharedPreferences(this)
		m_Pref = pref

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

		// ★ Android 13 (API 33) 以降の通知パーミッション要求処理
		checkNotificationPermission()

		DoIntent(intent)

		RegisterBroadcastReceiver()

		// 設定ロード
		m_iCurWayPoint = pref.getInt("key_waypoint", 0)
		m_strKmlFile = pref.getString("key_kml_file", null)
		m_fZoom = pref.getFloat("key_gmap_zoom", 1f)
		m_fNosigZoom = pref.getFloat("key_nosig_zoom", 16f)
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
		BindService()
	}

	override fun onWindowFocusChanged(hasFocus: Boolean) {
		super.onWindowFocusChanged(hasFocus)

		if (bDebug) Log.d("WpNavi", "WpNavi::onWindowFocusChanged")
		SetupMapIfNeeded()
	}

	override fun onPause() {
		if (bDebug) Log.d("WpNavi", "WpNavi::onPause")
		super.onPause()

		// サービス停止
		val iStatus =
			if (mService != null) mService!!.GetStatus() else WpNaviService.STATUS_IDLE
		UnbindService()

		m_Pref?.edit()?.let { ed ->
			// GMap カメラ位置保存
			if (m_Map != null) {
				val cam = m_Map!!.cameraPosition

				if (iStatus != WpNaviService.STATUS_NOSIG) {
					m_fZoom = cam.zoom
				}

				ed.putFloat("key_gmap_lng", cam.target.longitude.toFloat())
				ed.putFloat("key_gmap_lat", cam.target.latitude.toFloat())
				ed.putFloat("key_gmap_zoom", m_fZoom)
				ed.putInt("key_waypoint", m_iCurWayPoint)
				ed.putString("key_kml_file", m_strKmlFile)
				ed.putFloat("key_nosig_zoom", m_fNosigZoom)
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

	override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
		if (bDebug) Log.d("WpNavi", "WpNavi::onActivityResult")
		super.onActivityResult(requestCode, resultCode, data)
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

	/*** Google Maps  */
	private fun SetupMapIfNeeded() {
		if (m_Map != null) return

		(supportFragmentManager.findFragmentById(R.id.map) as? SupportMapFragment)?.getMapAsync(this)
	}

	override fun onMapReady(googleMap: GoogleMap) {
		m_Map = googleMap

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
			m_Map!!.isMyLocationEnabled = true
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

		val ui = m_Map!!.uiSettings

		ui.isZoomControlsEnabled = true
		ui.isMyLocationButtonEnabled = true
		ui.isScrollGesturesEnabled = true
		ui.isZoomGesturesEnabled = true

		// 渋滞情報
		val pref = m_Pref
		if (pref != null) {
			m_Map!!.isTrafficEnabled = pref.getBoolean("key_traffic_info", false)

			// Map 移動
			val cameraPos = CameraPosition.Builder()
				.target(
					LatLng(
						pref.getFloat("key_gmap_lat", 0f).toDouble(),
						pref.getFloat("key_gmap_lng", 0f).toDouble()
					)
				)
				.zoom(m_fZoom)
				.bearing(0f)
				.build()
			m_Map!!.moveCamera(CameraUpdateFactory.newCameraPosition(cameraPos))
		}

		// マーカークリックリスナー登録
		m_Map!!.setOnMarkerClickListener { marker ->
			SetCurWayPoint(marker.title.toString().substring(2).toInt() - 1)
			false
		}

		val tv = TypedValue()
		if (theme.resolveAttribute(android.R.attr.actionBarSize, tv, true)) {
			m_Map!!.setPadding(
				0,
				TypedValue.complexToDimensionPixelSize(tv.data, resources.displayMetrics),
				0,
				findViewById<View>(R.id.buttonPrevWp).height
			)
		}

		if (m_strKmlFile != null) LoadKML(m_strKmlFile, m_iCurWayPoint)
	}

	override fun onRequestPermissionsResult(
		requestCode: Int,
		permissions: Array<String>,
		grantResults: IntArray
	) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults)
		if (requestCode == REQUEST_LOCATION_PERMISSION) {
			if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
				if (m_Map != null) {
					if (ContextCompat.checkSelfPermission(
							this,
							Manifest.permission.ACCESS_FINE_LOCATION
						) == PackageManager.PERMISSION_GRANTED
						|| ContextCompat.checkSelfPermission(
							this,
							Manifest.permission.ACCESS_COARSE_LOCATION
						) == PackageManager.PERMISSION_GRANTED
					) {
						m_Map!!.isMyLocationEnabled = true
					}
				}
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
		if (m_Map != null && m_Markers.isNotEmpty()) {
			// 元 CurWP のアイコンを blue にする
			m_Markers[m_iCurWayPoint].setIcon(
				BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE)
			)

			m_Markers[iNewWp].setIcon(
				BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)
			)
		}
		m_iCurWayPoint = iNewWp
	}

	fun SetMoveCurWayPoint(iNewWp: Int) {
		if (m_Map != null && m_Markers.isNotEmpty()) {
			SetCurWayPoint(iNewWp)
			m_Markers[iNewWp].showInfoWindow()

			m_Map!!.animateCamera(CameraUpdateFactory.newLatLng(m_Markers[iNewWp].position))
		}
	}

	/*** Load KML  */
	fun LoadKML(strKmlFile: String?, iWayPoint: Int): Boolean {
		val nextDist = m_Pref?.getInt("key_NextDistance", 50) ?: 50
		val Info = m_WayPoint.LoadKML(strKmlFile, nextDist)

		if (Info.m_iErrorCode != 0) {
			Toast.makeText(this, Info.m_iErrorCode, Toast.LENGTH_LONG).show()
			return false
		}

		m_Map!!.clear()
		m_Markers.clear()
		m_iCurWayPoint = 0
		m_strKmlFile = strKmlFile

		// タイトル設定
		if (Info.m_strTitle != null) {
			title = Info.m_strTitle
		} else {
			setTitle(R.string.app_name)
		}

		// WP を Map に追加
		for (i in 0 until m_WayPoint.Size()) {
			val MakerOpt = MarkerOptions()
			MakerOpt.position(m_WayPoint.GetPoint(i))
			MakerOpt.title(String.format("WP%d", i + 1))
			MakerOpt.icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE))
			m_Markers.add(m_Map!!.addMarker(MakerOpt)!!)
		}

		val fDipScale = applicationContext.resources.displayMetrics.density

		// Line を Map に追加
		Info.m_Polyline.color(-0xee9901)
		Info.m_Polyline.width(6f * fDipScale)
		m_Map!!.addPolyline(Info.m_Polyline)

		val isReverse = m_Pref?.getBoolean("key_ReverseOrder", false) ?: false
		SetCurWayPoint(
			if (iWayPoint >= 0) iWayPoint else if (isReverse) m_WayPoint.Size() - 1 else 0
		)

		// ルートが 180W をまたいでいたら，補正
		if (Info.m_dMaxLng - Info.m_dMinLng > 180) {
			val tmp = Info.m_dMaxLng
			Info.m_dMaxLng = Info.m_dMinLng
			Info.m_dMinLng = tmp
		}

		// ルート全体に移動
		m_Map!!.moveCamera(
			CameraUpdateFactory.newLatLngBounds(
				LatLngBounds.builder()
					.include(LatLng(Info.m_dMaxLat, Info.m_dMaxLng))
					.include(LatLng(Info.m_dMinLat, Info.m_dMinLng))
					.build(),
				(16f * fDipScale).toInt() // padding
			)
		)

		return true
	}

	/*** Option menu  */
	override fun onCreateOptionsMenu(menu: Menu): Boolean {
		super.onCreateOptionsMenu(menu)
		menuInflater.inflate(R.menu.wp_navi, menu)
		return true
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean {
		val id = item.itemId

		if (id == R.id.itemLoadKML) {
			val fod = FileOpenDialog(
				this@WpNaviActivity,
				object : FileOpenDialogListener {
					override fun onFileSelected(file: File?) {
						this@WpNaviActivity.onFileSelected(file)
					}
				},
				FileOpenDialog.MODE_FILE
			) { pathname ->
				pathname.name.endsWith(".kml") ||
						pathname.name.endsWith(".kmz") ||
						pathname.name.endsWith(".xml")
			}
			fod.openDirectory(m_strKmlFile)
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

	override fun onFileSelected(file: File?) {
		file?.let {
			LoadKML(it.absolutePath, -1)
		}
	}

	/*** GME URL intent	 */
	override fun onNewIntent(intent: Intent) {
		if (bDebug) Log.d("WpNavi", "WpNavi::onNewIntent")
		super.onNewIntent(intent)
		DoIntent(intent)
	}

	fun DoIntent(intent: Intent?): Boolean {
		if (intent == null) return false
		if (bDebug) Log.d("WpNavi", "DoIntent:Action:" + intent.action)

		// notification から呼ばれた
		if (intent.getBooleanExtra("quit_service", false)) {
			if (bDebug) Log.d("WpNavi", "DoIntent:Killed by notification")
			m_bQuitService = true
			return true
		}

		// URL フィルタに引っかかった
		val strUrl = intent.dataString ?: return false
		if (strUrl != null) return DownloadURL(strUrl)

		return false
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
					val status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS))
					val reason = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_REASON))
					if (bDebug) {
						Log.d("WpNavi", "BBRcv:status=$status")
						Log.d("WpNavi", "BBRcf:reason=$reason")
					}

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

	/*** Service  */
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

	/*** 無電波モード	 */
	fun EnterNosigUI() {
		window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

		val btn = findViewById<Button>(R.id.buttonStartNavi)
		btn.text = getText(R.string.button_stop_navi) as String

		if (m_Map != null) {
			val camOld = m_Map!!.cameraPosition
			m_fZoom = camOld.zoom

			val camNew = CameraPosition.Builder()
				.target(camOld.target)
				.zoom(m_fNosigZoom)
				.tilt(75f)
				.build()
			m_Map!!.moveCamera(CameraUpdateFactory.newCameraPosition(camNew))
		}

		if (bDebug) Log.d(
			"WpNavi",
			"EnterNosigUI::z:$m_fZoom nz:$m_fNosigZoom"
		)
	}

	fun ExitNosigUI() {
		window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

		if (m_Map != null) {
			val camOld = m_Map!!.cameraPosition

			val camNew = CameraPosition.Builder()
				.target(camOld.target)
				.zoom(m_fZoom)
				.tilt(0f)
				.bearing(0f)
				.build()
			m_Map!!.moveCamera(CameraUpdateFactory.newCameraPosition(camNew))
		}

		if (bDebug) Log.d(
			"WpNavi",
			"ExitNosigUI::z:$m_fZoom nz:$m_fNosigZoom"
		)

		val btn = findViewById<Button>(R.id.buttonStartNavi)
		btn.text = getText(R.string.button_start_navi) as String
	}

	// 一定時間ごと地図位置更新
	fun OnLocationChanged(location: Location) {
		if (m_Map != null) {
			val camOld = m_Map!!.cameraPosition

			m_fNosigZoom = camOld.zoom

			val camNew = CameraPosition.Builder()
				.target(LatLng(location.latitude, location.longitude))
				.zoom(camOld.zoom)
				.tilt(75f)
				.bearing(location.bearing)
				.build()
			m_Map!!.animateCamera(CameraUpdateFactory.newCameraPosition(camNew))
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
