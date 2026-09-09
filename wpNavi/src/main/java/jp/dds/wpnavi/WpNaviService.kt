package jp.dds.wpnavi

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Message
import android.support.v4.app.NotificationCompat
import android.util.Log
import android.widget.Toast
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GooglePlayServicesUtil
import com.google.android.gms.common.api.GoogleApiClient
import com.google.android.gms.common.api.GoogleApiClient.ConnectionCallbacks
import com.google.android.gms.common.api.GoogleApiClient.OnConnectionFailedListener
import com.google.android.gms.location.LocationListener
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import java.io.DataOutputStream

class WpNaviService : Service(), ConnectionCallbacks, OnConnectionFailedListener, LocationListener {
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

	private var m_GoogleApiClient: GoogleApiClient? = null
	var m_MsgHandler: Handler? = null
	var m_Location: Location? = null

	/*** サービスハンドラ  */ /*
	@Override
	public void onCreate(){
		if( bDebug ) Log.d( "WpNavi", "Service::onCreate" );
	}
	*/
	override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
		if (!StartLocationUpdate()) {
			// GPS 取得失敗
			Toast.makeText(applicationContext, R.string.text_NoGPS, Toast.LENGTH_LONG).show()
		} else {
			WayPoint = KmlManager(intent.getIntArrayExtra("WayPoint")!!)

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
		return START_NOT_STICKY
	}

	override fun onDestroy() {
		if (bDebug) Log.d("WpNavi", "Service::onDestroy")
		CancelNotification()
		StopLocationUpdate()
		SetStatus(STATUS_IDLE)
	}

	override fun onBind(intent: Intent): IBinder? {
		if (bDebug) Log.d("WpNavi", "Service::onBind")
		return WpNaviServiceLocalBinder()
	}

	/*
	@Override
	public void onRebind( Intent intent ){
		if( bDebug ) Log.d( "WpNavi", "Service::onRebind" );
	}
	*/
	override fun onUnbind(intent: Intent): Boolean {
		if (bDebug) Log.d("WpNavi", "Service::onUnbind")
		m_MsgHandler = null
		return false
	}

	inner class WpNaviServiceLocalBinder : Binder() {
		val service: WpNaviService
			//サービスの取得
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
		// サービス状態を返す
		// ナビリスタートから 2秒以内は RESTART を返す
		return if ((m_iStatus == STATUS_RUNNING &&
					(System.currentTimeMillis() - iRestartTime) < (2000 + iWaitTime)
					)
		) STATUS_RESTART else m_iStatus
	}

	/*** ナビ起動  */
	fun StartNavi() {
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

	/*** GPS ハンドラ  */ // 電波なしナビモード開始・終了
	fun StartLocationUpdate(): Boolean {
		if (GooglePlayServicesUtil.isGooglePlayServicesAvailable(this) != ConnectionResult.SUCCESS) {
			return false
		}

		if (m_GoogleApiClient == null) {
			m_GoogleApiClient = GoogleApiClient.Builder(this)
				.addConnectionCallbacks(this)
				.addOnConnectionFailedListener(this)
				.addApi(LocationServices.API)
				.build()
		}

		if (!m_GoogleApiClient!!.isConnected) m_GoogleApiClient!!.connect()

		return true
	}

	// GPS 取得開始・終了
	fun StartLocationUpdate2() {
		val LocationRequest = LocationRequest()

		LocationRequest.setInterval(1000)
		LocationRequest.setFastestInterval(1000)
		LocationRequest.setPriority(com.google.android.gms.location.LocationRequest.PRIORITY_HIGH_ACCURACY)

		LocationServices.FusedLocationApi.requestLocationUpdates(
			m_GoogleApiClient, LocationRequest, this
		)
	}

	override fun onConnected(arg0: Bundle?) {
		if (bDebug) Log.d("WpNavi", "Service::onConnected")
		StartLocationUpdate2()
	}

	fun StopLocationUpdate() {
		if (m_GoogleApiClient != null) {
			if (m_GoogleApiClient!!.isConnected) {
				LocationServices.FusedLocationApi.removeLocationUpdates(
					m_GoogleApiClient, this
				)
			}
			m_GoogleApiClient!!.disconnect()
		}
	}

	override fun onConnectionFailed(arg0: ConnectionResult) {
		if (bDebug) Log.d("WpNavi", "Service::onConnectionFailed")
	}

	fun onDisconnected() {
		if (bDebug) Log.d("WpNavi", "Service::onDisconnected")
	}

	override fun onConnectionSuspended(arg0: Int) {
		if (bDebug) Log.d("WpNavi", "Service::onConnectionSuspended")
	}

	override fun onLocationChanged(location: Location) {
		m_Location = location


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

		if (bDebug) Log.d(
			"WpNavi",
			("st:" + m_iStatus
					+ " wp:" + iCurWayPoint
					+ " r:" + bWpReached
					+ " d:" + (WayPoint!!.Distance(
				iCurWayPoint,
				location.longitude,
				location.latitude
			).toInt())
					+ " GPS lon=" + location.longitude + " lat=" + location.latitude)
		)


		// Wp# 更新，最終 Wp に到達したら終了
		if (bWpReached && (if (bReverseOrder) --iCurWayPoint < 0 else ++iCurWayPoint >= WayPoint!!.Size()
					)
		) {
			if (bDebug) Log.d("WpNavi", "Destination reached")
			StopNavi()
		} else if ((bWpReached || m_iStatus == STATUS_NOSIG) && IsNetworkAlive()) {
			// 電波があって，and
			//   Wp に到達する or
			//   いままで NOSIG だった (電波が復活した)
			// であるなら，次のナビを起動する
			if (bDebug) Log.d("WpNavi", "Network re-connected")

			StartNavi()
		} else if (bWpReached && m_iStatus == STATUS_RUNNING) {
			// STATUS_RUNNING で WP に到達した時に電波がない状態．
			// Google ナビを閉じて WpNavi を前面に出す．
			if (bDebug) Log.d("WpNavi", "Network disconnected")

			SetStatus(STATUS_NOSIG)
			CancelNotification()

			val intent = Intent(Intent.ACTION_VIEW)
			intent.setClassName("jp.dds.wpnavi", "jp.dds.wpnavi.WpNaviActivity")
			intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
			startActivity(intent)
		} else if (m_iStatus == STATUS_NOSIG && m_MsgHandler != null) {
			// 位置表示更新
			if (bDebug) Log.d("WpNavi", "normal update")

			val Msg = Message()
			Msg.what = if (bWpReached) MSG_UPDATE_WP else MSG_UPDATE
			m_MsgHandler!!.sendMessage(Msg)
		}
	}

	// 電波状態取得
	private fun IsNetworkAlive(): Boolean {
		val Info = (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager)
			.activeNetworkInfo

		return Info != null && Info.isConnected
		//return false && Info != null && Info.isConnected();
		//return iCurWayPoint < 3;
	}

	/*** Notification  */
	fun SetNotification() {
		val strNotifyMsg =
			String.format(resources.getText(R.string.text_Activated) as String, iCurWayPoint + 1)

		// notification 設定
		if (notificationManager == null) {
			notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
		}

		val intent = Intent(Intent.ACTION_VIEW)
		intent.setClassName("jp.dds.wpnavi", "jp.dds.wpnavi.WpNaviActivity")
		intent.putExtra("quit_service", true)

		// Android 12 (API 31) 以降向けの FLAG_IMMUTABLE 対応
		val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
		} else {
			PendingIntent.FLAG_UPDATE_CURRENT
		}

		// intentの設定
		val contentIntent =
			PendingIntent.getActivity(this, 0, intent, pendingIntentFlags)

		val channelId = "wpnavi_service_channel"

		val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			// Android 8.0 (API 26) 以上は標準の Notification.Builder を使用
			val channel = NotificationChannel(
				channelId,
				"WpNavi Service",
				NotificationManager.IMPORTANCE_LOW
			)
			notificationManager!!.createNotificationChannel(channel)

			android.app.Notification.Builder(this, channelId)
				.setContentIntent(contentIntent)
				.setTicker(strNotifyMsg)
				.setSmallIcon(R.drawable.ic_notify)
				.setContentTitle(strNotifyMsg)
				.setContentText(resources.getText(R.string.app_name))
				.setWhen(System.currentTimeMillis())
				.setAutoCancel(false)
				.setOngoing(true)
				.build()
		} else {
			// Android 7.1 以下は Support Library の Builder を使用
			@Suppress("DEPRECATION")
			NotificationCompat.Builder(this@WpNaviService)
				.setContentIntent(contentIntent)
				.setTicker(strNotifyMsg)
				.setSmallIcon(R.drawable.ic_notify)
				.setContentTitle(strNotifyMsg)
				.setContentText(resources.getText(R.string.app_name))
				.setWhen(System.currentTimeMillis())
				.setAutoCancel(false)
				.setOngoing(true)
				.build()
		}

		notificationManager!!.notify(R.string.app_name, notification)
	}

	fun CancelNotification() {
		if (notificationManager != null) notificationManager!!.cancelAll()
		notificationManager = null
	}

	/*** Kill Google Maps  */
	fun KillGMaps() {
		val process: Process
		if (!bKillByRoot) return
		try {
			process = Runtime.getRuntime().exec("su")
			val dos = DataOutputStream(process.outputStream)
			dos.writeBytes("/system/bin/killall com.google.android.apps.maps\n")
			dos.close()

			process.waitFor()
		} catch (e: Exception) {
		}
	}

	companion object {
		private val bDebug: Boolean = WpNaviActivity.Companion.bDebug

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
