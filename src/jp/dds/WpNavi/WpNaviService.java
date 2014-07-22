package jp.dds.WpNavi;

import java.io.DataOutputStream;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

public class WpNaviService extends Service implements LocationListener{
	static final boolean bDebug = WpNaviActivity.bDebug;

	static final int STATUS_NORMAL	= 0;
	static final int STATUS_RESTART	= 0x80000000;

	Coordinate	WayPoint;
	int		iCurWayPoint	= 0;
	long	iRestartTime	= 0;

	/************************************************************************/

	LocationManager mLocationManager = null;
	NotificationManager notificationManager = null;

	@Override
	public void onCreate(){
		if( bDebug ) Log.d( "WpNavi", "Service::onCreate" );
	}

	/*** サービスハンドラ ***************************************************/

	@Override
	public int onStartCommand( Intent intent, int flags, int startId ){

		GetLocationManager();

		WayPoint = new Coordinate( intent.getIntegerArrayListExtra( "WayPoint" ));
		iCurWayPoint = intent.getIntExtra( "CurWayPoint", 0 );

		if( bDebug ) Log.d( "WpNavi",
			String.format(
				"Service::onStartCommand:WP=%d num=%d",
				iCurWayPoint, WayPoint.Length()
			)
		);

		StartNavi();
		return START_NOT_STICKY;
	}

	@Override
	public void onDestroy(){
		if( bDebug ) Log.d( "WpNavi", "Service::onDestroy" );
		CancelNotification();
		RemoveLocationManager();
	}

	@Override
	public IBinder onBind( Intent intent ){
		if( bDebug ) Log.d( "WpNavi", "Service::onBind" );
		return new WpNaviServiceLocalBinder();
	}

	@Override
	public void onRebind(Intent intent) {
		if( bDebug ) Log.d( "WpNavi", "Service::onRebind" );
	}

	@Override
	public boolean onUnbind( Intent intent ){
		if( bDebug ) Log.d( "WpNavi", "Service::onUnbind" );
		return true;
	}

	public void Stop(){
		CancelNotification();
		RemoveLocationManager();
		stopSelf();
	}

    public class WpNaviServiceLocalBinder extends Binder {
        //サービスの取得
        WpNaviService getService(){
            return WpNaviService.this;
        }
    }

	int GetStatus(){
		// サービス状態を返す
		// ナビリスタートから 5秒以内は RESTART を返す
		return ( System.currentTimeMillis() - iRestartTime ) < 5000 ?
			STATUS_RESTART : iCurWayPoint;
	}

    /*** ナビ起動 ***************************************************************/

	final void StartNavi(){
		if( bDebug ) Log.d( "WpNavi", "StartNavi:WP" + iCurWayPoint + ":" +
			WayPoint.GetLng( iCurWayPoint ) + "," +
			WayPoint.GetLat( iCurWayPoint )
		);

		SetNotification();
		iRestartTime = System.currentTimeMillis();

		KillGMaps();

		// インテントを投げる
		Intent i = new Intent();
		i.setAction( Intent.ACTION_VIEW );
		i.setFlags( Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK );
		i.setClassName( "com.google.android.apps.maps", "com.google.android.maps.driveabout.app.NavigationActivity" );
		Uri uri = Uri.parse( "google.navigation:///?ll=" +
			WayPoint.GetLat( iCurWayPoint ) + "," +
			WayPoint.GetLng( iCurWayPoint ) + "&q=WP" + ( iCurWayPoint + 1 )
		);
		i.setData(uri);
		startActivity(i);
	}

	/*** GPS ハンドラ ***********************************************************/

	final void GetLocationManager(){
		if( mLocationManager != null ) return;

		// 位置情報取得
		mLocationManager = ( LocationManager )getSystemService( Context.LOCATION_SERVICE );

		/*
		// Criteriaオブジェクトを生成
		Criteria criteria = new Criteria();
		criteria.setAccuracy( Criteria.ACCURACY_FINE );
		criteria.setPowerRequirement( Criteria.POWER_MEDIUM );
		criteria.setBearingRequired( false );
		criteria.setSpeedRequired( false );
		criteria.setAltitudeRequired( false );

		String provider = mLocationManager.getBestProvider( criteria, true );

		// 取得したロケーションプロバイダを表示
		if( bDebug ) Log.d( "WpNavi", "GPS provider:" + provider );
		*/

		// LocationListenerを登録
		mLocationManager.requestLocationUpdates( LocationManager.GPS_PROVIDER, 1000, 0, this );
	}

	final void RemoveLocationManager(){
		if( mLocationManager != null ){
			mLocationManager.removeUpdates( this );
			mLocationManager = null;
		}
	}

	static int iCnt = 0;

	@Override
	public void onLocationChanged( Location location ){
		if( bDebug ) Log.d( "WpNavi", "GPS lon=" + location.getLongitude() + " lat=" + location.getLatitude() );

		if( ++iCnt >= 15 ){
			iCnt = 0;
			if( iCurWayPoint < WayPoint.Length() - 1 ){
				++iCurWayPoint;
				StartNavi();
			}
		}
	}

	@Override
	public void onProviderDisabled(String provider) {
		// TODO Auto-generated method stub
	}

	@Override
	public void onProviderEnabled(String provider) {
		// TODO Auto-generated method stub
	}

	@Override
	public void onStatusChanged(String provider, int status, Bundle extras) {
		// TODO Auto-generated method stub
	}

	/*** Notification *******************************************************/
	
	void SetNotification(){
		String strNotifyMsg = String.format(( String )getResources().getText( R.string.text_Activated ), iCurWayPoint + 1 );

		// notification 設定
		if( notificationManager == null ){
			notificationManager = ( NotificationManager )getSystemService( NOTIFICATION_SERVICE );
		}
		
		Notification notification = new Notification(
			android.R.drawable.ic_menu_directions,
			strNotifyMsg,
			System.currentTimeMillis()
		);
		notification.flags = Notification.FLAG_ONGOING_EVENT;

		Intent intent = new Intent( Intent.ACTION_VIEW );
		intent.setClassName( "jp.dds.WpNavi", "jp.dds.WpNavi.WpNaviActivity" );

		//intentの設定
		PendingIntent contentIntent = PendingIntent.getActivity( this, 0, intent, 0 );

		notification.setLatestEventInfo(
			getApplicationContext(),
			getResources().getText( R.string.app_name ),
			strNotifyMsg,
			contentIntent
		);
		notificationManager.notify( R.string.app_name, notification );
	}
	
	void CancelNotification(){
		if( notificationManager != null ) notificationManager.cancelAll();
		notificationManager = null;
	}
	
	/*** Kill Google Maps ***************************************************/

	final void KillGMaps(){
		Process process;

		try{
			process = Runtime.getRuntime().exec( "su" );
			DataOutputStream dos = new DataOutputStream( process.getOutputStream());
			dos.writeBytes(
				"gmap=com.google.android.apps.maps;while ps|grep -q $gmap;do kill -9 `ps|grep $gmap|awk '{ print $2 }'`;done;exit\n"
			);
			dos.close();

			process.waitFor();
		}catch( Exception e ){}
	}
}
