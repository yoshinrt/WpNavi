package jp.dds.wpnavi;

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
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.widget.Toast;

public class WpNaviService extends Service implements LocationListener{
	private static final boolean bDebug = WpNaviActivity.bDebug;
	private static final boolean bRestartTest = false;

	static final int STATUS_IDLE	= 0;
	static final int STATUS_NORMAL	= 1;
	static final int STATUS_RESTART	= 2;

	private Coordinate	WayPoint;

	int		iCurWayPoint	= 0;
	int		iNextDistance	= 50;
	int		iWaitTime		= 0;
	boolean	bReverseOrder	= false;
	boolean	bKillByRoot		= false;

	private long		iRestartTime	= 0;
	private boolean	bRunning		= false;

	private LocationManager		mLocationManager	= null;
	private NotificationManager	notificationManager	= null;

	/*** サービスハンドラ ***************************************************/

	/*
	@Override
	public void onCreate(){
		if( bDebug ) Log.d( "WpNavi", "Service::onCreate" );
	}
	*/

	@Override
	public int onStartCommand( Intent intent, int flags, int startId ){
		if( GetLocationManager() == false ){
			// GPS 取得失敗
			Toast.makeText( getApplicationContext(), R.string.text_NoGPS, Toast.LENGTH_LONG ).show();
		}else{
			WayPoint = new Coordinate( intent.getIntegerArrayListExtra( "WayPoint" ));
			
			if( bDebug ) Log.d( "WpNavi",
				String.format(
					"Service::onStartCommand:WP=%d num=%d",
					iCurWayPoint, WayPoint.Size()
				)
			);
			StartNavi();
		}
		return START_NOT_STICKY;
	}

	@Override
	public void onDestroy(){
		if( bDebug ) Log.d( "WpNavi", "Service::onDestroy" );
		CancelNotification();
		RemoveLocationManager();
		bRunning = false;
	}

	@Override
	public IBinder onBind( Intent intent ){
		if( bDebug ) Log.d( "WpNavi", "Service::onBind" );
		return new WpNaviServiceLocalBinder();
	}

	/*
	@Override
	public void onRebind( Intent intent ){
		if( bDebug ) Log.d( "WpNavi", "Service::onRebind" );
	}

	@Override
	public boolean onUnbind( Intent intent ){
		if( bDebug ) Log.d( "WpNavi", "Service::onUnbind" );
		return false;
	}
	*/

    public class WpNaviServiceLocalBinder extends Binder{
        //サービスの取得
        WpNaviService getService(){
            return WpNaviService.this;
        }
    }

	int GetStatus(){
		// サービス状態を返す
		// ナビリスタートから 2秒以内は RESTART を返す
		return
			!bRunning ? STATUS_IDLE :
			( System.currentTimeMillis() - iRestartTime ) < ( 2000 + iWaitTime ) ?
			STATUS_RESTART : STATUS_NORMAL;
	}

    /*** ナビ起動 ***************************************************************/

	final void StartNavi(){
		if( bDebug ) Log.d( "WpNavi", "StartNavi:WP" + iCurWayPoint + ":" +
			WayPoint.GetLng( iCurWayPoint ) + "," +
			WayPoint.GetLat( iCurWayPoint )
		);

		bRunning = true;
		SetNotification();
		iRestartTime = System.currentTimeMillis();

		KillGMaps();
		try{ Thread.sleep( iWaitTime ); }catch( InterruptedException e ){}

		// インテントを投げる
		Intent i = new Intent();
		i.setAction( Intent.ACTION_VIEW );
		i.setFlags( Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK );
		i.setClassName( "com.google.android.apps.maps", "com.google.android.maps.driveabout.app.NavigationActivity" );
		Uri uri = Uri.parse( "google.navigation:///?ll=" +
			WayPoint.GetLat( iCurWayPoint ) + "," +
			WayPoint.GetLng( iCurWayPoint ) + "&q=WP" + ( iCurWayPoint + 1 )
		);
		i.setData( uri );
		startActivity( i );
	}

	public void StopNavi(){
		bRunning = false;
		CancelNotification();
		RemoveLocationManager();
		stopSelf();
	}

	/*** GPS ハンドラ ***********************************************************/

	final boolean GetLocationManager(){
		if( mLocationManager == null ){
			// 位置情報取得
			mLocationManager = ( LocationManager )getSystemService( Context.LOCATION_SERVICE );
		}
		
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
		try{
			mLocationManager.requestLocationUpdates( LocationManager.GPS_PROVIDER, 1000, 0, this );
		}catch( Exception e ){
			return false;
		}
		
		return true;
	}

	final void RemoveLocationManager(){
		if( mLocationManager != null ){
			mLocationManager.removeUpdates( this );
			mLocationManager = null;
		}
	}

	private static int iCnt = 0;

	@Override
	public void onLocationChanged( Location location ){
		//if( bDebug ) Log.d( "WpNavi", "GPS lon=" + location.getLongitude() + " lat=" + location.getLatitude());

		if( !bRestartTest ){
			// 経由地に近づいたらナビ起動
			double dDistance = WayPoint.DistancePow2( iCurWayPoint, location.getLongitude(), location.getLatitude());
			if( dDistance <= ( iNextDistance * iNextDistance ) && (
				bReverseOrder ?
					--iCurWayPoint >= 0 :
					++iCurWayPoint < WayPoint.Size()
			)){
				StartNavi();
			}
			if( iCurWayPoint == ( bReverseOrder ? 0 : WayPoint.Size() - 1 )) StopNavi();
		}else if( ++iCnt >= 15 ){
			// テスト用，規定時間でナビ起動
			iCnt = 0;
			if( ++iCurWayPoint < WayPoint.Size()) StartNavi();
			if( iCurWayPoint == WayPoint.Size() - 1 ) StopNavi();
		}
	}

	@Override
	public void onProviderDisabled( String provider ){}

	@Override
	public void onProviderEnabled( String provider ){}

	@Override
	public void onStatusChanged( String provider, int status, Bundle extras ){}

	/*** Notification *******************************************************/

	void SetNotification(){
		String strNotifyMsg = String.format(( String )getResources().getText( R.string.text_Activated ), iCurWayPoint + 1 );

		// notification 設定
		if( notificationManager == null ){
			notificationManager = ( NotificationManager )getSystemService( NOTIFICATION_SERVICE );
		}

		Intent intent = new Intent( Intent.ACTION_VIEW );
		intent.setClassName( "jp.dds.wpnavi", "jp.dds.wpnavi.WpNaviActivity" );
		intent.putExtra( "quit_service", true );

		//intentの設定
		PendingIntent contentIntent = PendingIntent.getActivity( this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT );
		
		// LargeIcon の Bitmap を生成
		//Bitmap largeIcon = BitmapFactory.decodeResource( getResources(), R.drawable.ic_launcher );
		
		// NotificationBuilderを作成
		Notification notification = new NotificationCompat.Builder( WpNaviService.this )
			.setContentIntent( contentIntent )
			.setTicker( strNotifyMsg )
			.setSmallIcon( R.drawable.ic_notify )
			.setContentTitle( strNotifyMsg )
			.setContentText( getResources().getText( R.string.app_name ))
			//.setLargeIcon( largeIcon )
			.setWhen( System.currentTimeMillis())
			.setAutoCancel( false )
			.setOngoing( true )
			.build();
		
		notificationManager.notify( R.string.app_name, notification );
	}

	void CancelNotification(){
		if( notificationManager != null ) notificationManager.cancelAll();
		notificationManager = null;
	}

	/*** Kill Google Maps ***************************************************/

	final void KillGMaps(){
		Process process;
		if( !bKillByRoot ) return;
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
