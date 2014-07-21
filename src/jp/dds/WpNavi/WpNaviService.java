package jp.dds.WpNavi;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

public class WpNaviService extends Service implements LocationListener{
	static final boolean bDebug = WpNaviActivity.bDebug;

	LocationManager mLocationManager = null;
	NotificationManager notificationManager = null;

	@Override
	public void onCreate(){
		if( bDebug ) Log.d( "WpNavi", "Service::onCreate" );

		// notification 設定
		notificationManager = ( NotificationManager )getSystemService( NOTIFICATION_SERVICE );

		Notification notification = new Notification(
			android.R.drawable.btn_default,
			getResources().getText( R.string.text_Activated ),
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
			getResources().getText( R.string.text_Activated ),
			contentIntent
		);
		notificationManager.notify( R.string.app_name, notification );

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

	/*** サービスハンドラ ***************************************************/

	@Override
	public int onStartCommand( Intent intent, int flags, int startId ){
		if( bDebug ) Log.d( "WpNavi", "Service::onStartCommand" );
		return START_NOT_STICKY;
	}

	@Override
	public void onDestroy(){
		if( bDebug ) Log.d( "WpNavi", "Service::onDestroy" );
		if( notificationManager != null ) notificationManager.cancelAll();
		if( mLocationManager != null ) mLocationManager.removeUpdates( this );
	}

	@Override
	public IBinder onBind( Intent intent ){
		if( bDebug ) Log.d( "WpNavi", "Service::onBind" );
		return null;
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

    public class WpNaviServiceLocalBinder extends Binder {
        //サービスの取得
        WpNaviService getService(){
            return WpNaviService.this;
        }
    }

    /*** GPS ハンドラ *******************************************************/

	@Override
	public void onLocationChanged( Location location ){
		if( bDebug ) Log.d( "WpNavi", "GPS lon=" + location.getLongitude() + " lat=" + location.getLatitude() );

		/*
		// 緯度の表示
		TextView tv_lat = (TextView) findViewById(R.id.Latitude);
		tv_lat.setText("Latitude:"+location.getLatitude());

		// 経度の表示
		TextView tv_lng = (TextView) findViewById(R.id.Longitude);
		tv_lng.setText("Latitude:"+location.getLongitude());
		*/
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
}
