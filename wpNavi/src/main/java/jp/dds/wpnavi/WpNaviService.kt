package jp.dds.wpnavi

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.DataOutputStream

class WpNaviService : Service() {
	private var WayPoint: KmlManager? = null

	var iCurWayPoint: Int = 0
	var iNextDistance: Int = 50
	var iWaitTime: Int = 0
	var bReverseOrder: Boolean = false
	var bKillByRoot: Boolean = false
	var bRestartTest: Boolean = false

	private var iRestartTime: Long = 0
	private var m_iStatus = STATUS_IDLE

	private var notificationManager: NotificationManager? = null

	private lateinit var locationManager: LocationManager
	private lateinit var locationListener: LocationListener

	var m_MsgHandler: Handler? = null
	var m_Location: Location? = null

	override fun onCreate() {
		super.onCreate()

		// LocationManager の初期化
		locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

		// 位置情報更新リスナーの定義
		locationListener = LocationListener { location ->
			onLocationChanged(location)
		}
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		if (intent == null || !intent.hasExtra("WayPoint")) {
			StopNavi()
			return START_NOT_STICKY
		}

		if (!StartLocationUpdate()) {
			// GPS 取得失敗
			Toast.makeText(applicationContext, R.string.text_NoGPS, Toast.LENGTH_LONG).show()
		} else {
			val wpArray = intent.getIntArrayExtra("WayPoint")
			if (wpArray != null) {
				WayPoint = KmlManager(wpArray)

				if (bDebug) Log.d(
					"WpNavi",
					String.format(
						"Service::onStartCommand:WP=%d num=%d",
						iCurWayPoint, WayPoint!!.Size()
					)
				)

				if (IsNetworkAlive()) {
					// 電波があれば Google ナビ起動
					StartNavi()
				} else {
					SetStatus(STATUS_NOSIG)
				}
			}
		}
		return START_STICKY
	}

	override fun onDestroy() {
		if (bDebug) Log.d("WpNavi", "Service::onDestroy")
		CancelNotification()
		StopLocationUpdate()
		SetStatus(STATUS_IDLE)
		super.onDestroy()
	}

	override fun onBind(intent: Intent): IBinder {
		if (bDebug) Log.d("WpNavi", "Service::onBind")
		return WpNaviServiceLocalBinder()
	}

	override fun onUnbind(intent: Intent): Boolean {
		if (bDebug) Log.d("WpNavi", "Service::onUnbind")
		m_MsgHandler = null
		return false
	}

	inner class WpNaviServiceLocalBinder : Binder() {
		val service: WpNaviService
			get() = this@WpNaviService
	}

	fun SetStatus(iStatus: Int) {
		val iPrevStat = m_iStatus
		m_iStatus = iStatus
		if (bDebug) Log.d(
			"WpNavi",
			"Service status $iPrevStat->$iStatus"
		)

		if (m_MsgHandler != null && iPrevStat != iStatus) {
			val Msg = Message()
			Msg.what = MSG_CHG_STATE
			Msg.arg1 = iPrevStat
			m_MsgHandler!!.sendMessage(Msg)
		}
	}

	fun GetStatus(): Int {
		return if ((m_iStatus == STATUS_RUNNING &&
					(System.currentTimeMillis() - iRestartTime) < (2000 + iWaitTime)
					)
		) STATUS_RESTART else m_iStatus
	}

	/*** ナビ起動  */
	fun StartNavi() {
		if (WayPoint == null || iCurWayPoint >= WayPoint!!.Size()) return

		if (bDebug) Log.d(
			"WpNavi", "StartNavi:WP" + iCurWayPoint + ":" +
					WayPoint!!.GetLng(iCurWayPoint) + "," +
					WayPoint!!.GetLat(iCurWayPoint)
		)

		SetStatus(STATUS_RUNNING)

		SetNotification()
		iRestartTime = System.currentTimeMillis()

		KillGMaps()
		try {
			Thread.sleep(iWaitTime.toLong())
		} catch (e: InterruptedException) {
		}

		// インテントを投げる
		val i = Intent()
		i.setAction(Intent.ACTION_VIEW)
		i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
		i.setClassName(
			"com.google.android.apps.maps",
			"com.google.android.maps.driveabout.app.NavigationActivity"
		)
		val uri = Uri.parse(
			"google.navigation:///?ll=" +
					WayPoint!!.GetLat(iCurWayPoint) + "," +
					WayPoint!!.GetLng(iCurWayPoint) + "&q=WP" + (iCurWayPoint + 1)
		)
		i.setData(uri)
		startActivity(i)
	}

	fun StopNavi() {
		SetStatus(STATUS_IDLE)
		CancelNotification()
		StopLocationUpdate()
		stopSelf()
	}

	/*** GPS ハンドラ  */
	fun StartLocationUpdate(): Boolean {
		// GPSプロバイダが有効になっているかチェック
		if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
			return false
		}
		StartLocationUpdate2()
		return true
	}

	fun StartLocationUpdate2() {
		try {
			// GPS_PROVIDER から直接位置情報アップデートを受け取る
			locationManager.requestLocationUpdates(
				LocationManager.GPS_PROVIDER,
				1000L, // 最小更新間隔 (ms)
				0f,	   // 最小更新距離 (m)
				locationListener,
				Looper.getMainLooper()
			)
		} catch (e: SecurityException) {
			if (bDebug) Log.e("WpNavi", "Location permission missing", e)
		}
	}

	fun StopLocationUpdate() {
		try {
			locationManager.removeUpdates(locationListener)
		} catch (e: Exception) {
			if (bDebug) Log.e("WpNavi", "Failed to remove location updates", e)
		}
	}

	private fun onLocationChanged(location: Location) {
		m_Location = location

		if (WayPoint == null) return

		// 経由地に近づいたらナビ起動
		val bWpReached = ((bRestartTest && (((iDebugNavStartCnt + 1) and 0xF).also {
			iDebugNavStartCnt = it
		}) == 0) ||
				WayPoint!!.InDistance(
					iNextDistance,
					iCurWayPoint,
					location.longitude,
					location.latitude
				)
				)

		if (bDebug){
			val isMock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
				location.isMock
			} else {
				@Suppress("DEPRECATION")
				location.isFromMockProvider
			}

			Log.d(
				"WpNavi",
				("st:" + m_iStatus
						+ " wp:" + iCurWayPoint
						+ " r:" + bWpReached
						+ " d:" + (WayPoint!!.Distance(iCurWayPoint, location.longitude, location.latitude).toInt())
						+ " GPS lon=" + location.longitude + " lat=" + location.latitude
						+ " mock:$isMock"
						)
			)
		}

		// Wp# 更新，最終 Wp に到達したら終了
		if (bWpReached && (if (bReverseOrder) --iCurWayPoint < 0 else ++iCurWayPoint >= WayPoint!!.Size())) {
			if (bDebug) Log.d("WpNavi", "Destination reached")
			StopNavi()
		} else if ((bWpReached || m_iStatus == STATUS_NOSIG) && IsNetworkAlive()) {
			if (bDebug) Log.d("WpNavi", "Network re-connected")
			StartNavi()
		} else if (bWpReached && m_iStatus == STATUS_RUNNING) {
			if (bDebug) Log.d("WpNavi", "Network disconnected")
			SetStatus(STATUS_NOSIG)
			CancelNotification()

			val intent = Intent(Intent.ACTION_VIEW)
			intent.setClassName("jp.dds.wpnavi", "jp.dds.wpnavi.WpNaviActivity")
			intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
			startActivity(intent)
		} else if (m_iStatus == STATUS_NOSIG && m_MsgHandler != null) {
			if (bDebug) Log.d("WpNavi", "normal update")
			val Msg = Message()
			Msg.what = if (bWpReached) MSG_UPDATE_WP else MSG_UPDATE
			m_MsgHandler!!.sendMessage(Msg)
		}
	}

	// 電波状態取得
	private fun IsNetworkAlive(): Boolean {
		val connectivityManager =
			getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
		val info = connectivityManager.activeNetworkInfo
		return info != null && info.isConnected
	}

	/*** Notification  */
	fun SetNotification() {
		val strNotifyMsg =
			String.format(resources.getText(R.string.text_Activated) as String, iCurWayPoint + 1)

		if (notificationManager == null) {
			notificationManager =
				getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
		}

		val intent = Intent(Intent.ACTION_VIEW)
		intent.setClassName("jp.dds.wpnavi", "jp.dds.wpnavi.WpNaviActivity")
		intent.putExtra("quit_service", true)

		val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
		} else {
			PendingIntent.FLAG_UPDATE_CURRENT
		}

		val contentIntent = PendingIntent.getActivity(this, 0, intent, pendingIntentFlags)
		val channelId = "wpnavi_service_channel"

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			val channel = NotificationChannel(
				channelId,
				"WpNavi Service",
				NotificationManager.IMPORTANCE_LOW
			)
			notificationManager!!.createNotificationChannel(channel)
		}

		val notification = NotificationCompat.Builder(this, channelId)
			.setContentIntent(contentIntent)
			.setTicker(strNotifyMsg)
			.setSmallIcon(R.drawable.ic_notify)
			.setContentTitle(strNotifyMsg)
			.setContentText(resources.getText(R.string.app_name))
			.setWhen(System.currentTimeMillis())
			.setAutoCancel(false)
			.setOngoing(true)
			.build()

		notificationManager!!.notify(R.string.app_name, notification)

		// Android 14 (API 34) 対応の startForeground 呼び出し
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			ServiceCompat.startForeground(
				this,
				R.string.app_name,
				notification,
				ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
			)
		} else {
			startForeground(R.string.app_name, notification)
		}
	}

	fun CancelNotification() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
			stopForeground(STOP_FOREGROUND_REMOVE)
		} else {
			@Suppress("DEPRECATION")
			stopForeground(true)
		}

		if (notificationManager != null) notificationManager!!.cancelAll()
		notificationManager = null
	}

	/*** Kill Google Maps  */
	fun KillGMaps() {
		if (!bKillByRoot) return
		try {
			val process = Runtime.getRuntime().exec("su")
			val dos = DataOutputStream(process.outputStream)
			dos.writeBytes("/system/bin/killall com.google.android.apps.maps\n")
			dos.close()
			process.waitFor()
		} catch (e: Exception) {
			if (bDebug) Log.e("WpNavi", "Failed to kill Google Maps via root", e)
		}
	}

	companion object {
		private val bDebug: Boolean = WpNaviActivity.bDebug

		const val STATUS_IDLE: Int = 0
		const val STATUS_RESTART: Int = 1
		const val STATUS_RUNNING: Int = 2
		const val STATUS_NOSIG: Int = 3

		const val MSG_UPDATE: Int = 0
		const val MSG_UPDATE_WP: Int = 1
		const val MSG_CHG_STATE: Int = 2

		private var iDebugNavStartCnt = 0
	}
}