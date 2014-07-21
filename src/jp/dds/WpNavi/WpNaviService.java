package jp.dds.WpNavi;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

public class WpNaviService extends Service {
	static final boolean bDebug = WpNaviActivity.bDebug;
	
	NotificationManager notificationManager = null;
	
	@Override
	public void onCreate(){
		if( bDebug ) Log.d( "WpNavi", "Service::onCreate" );

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
	}

	@Override
	public int onStartCommand( Intent intent, int flags, int startId ){
		if( bDebug ) Log.d( "WpNavi", "Service::onStartCommand" );
		return START_NOT_STICKY;
	}

	@Override
	public void onDestroy(){
		if( bDebug ) Log.d( "WpNavi", "Service::onDestroy" );
		if( notificationManager != null ) notificationManager.cancelAll();
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
}
