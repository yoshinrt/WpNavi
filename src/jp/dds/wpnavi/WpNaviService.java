package jp.dds.wpnavi;

import java.io.DataOutputStream;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.GoogleApiClient.ConnectionCallbacks;
import com.google.android.gms.common.api.GoogleApiClient.OnConnectionFailedListener;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.widget.Toast;

public class WpNaviService extends Service
	implements ConnectionCallbacks, OnConnectionFailedListener, LocationListener
{
	private static final boolean bDebug = WpNaviActivity.bDebug;
	private static final boolean bRestartTest = false;

	static final int STATUS_IDLE	= 0;
	static final int STATUS_RESTART	= 1;
	static final int STATUS_RUNNING	= 2;
	static final int STATUS_NOSIG	= 3;
	
	static final int MSG_UPDATE		= 0;
	static final int MSG_CHG_STATE	= 1;
	
	private Coordinate	WayPoint;

	int		iCurWayPoint	= 0;
	int		iNextDistance	= 50;
	int		iWaitTime		= 0;
	boolean	bReverseOrder	= false;
	boolean	bKillByRoot		= false;

	private long	iRestartTime	= 0;
	private int		m_iStatus		= STATUS_IDLE;

	private NotificationManager	notificationManager	= null;
	
	private	GoogleApiClient m_GoogleApiClient	= null;
	public	Handler		m_MsgHandler			= null;
	private	Message		Msg					= new Message();
	public	Location	m_Location				= null;

	/*** サービスハンドラ ***************************************************/

	/*
	@Override
	public void onCreate(){
		if( bDebug ) Log.d( "WpNavi", "Service::onCreate" );
	}
	*/

	@Override
	public int onStartCommand( Intent intent, int flags, int startId ){
		if( !StartLocationUpdate()){
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
			
			if( IsNetworkAlive()){
				// 電波があれば Google ナビ起動
				StartNavi();
			}else{
				SetStatus( STATUS_NOSIG );
			}
		}
		return START_NOT_STICKY;
	}

	@Override
	public void onDestroy(){
		if( bDebug ) Log.d( "WpNavi", "Service::onDestroy" );
		CancelNotification();
		StopLocationUpdate();
		SetStatus( STATUS_IDLE );
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
	*/

	@Override
	public boolean onUnbind( Intent intent ){
		if( bDebug ) Log.d( "WpNavi", "Service::onUnbind" );
		m_MsgHandler = null;
		return false;
	}

    public class WpNaviServiceLocalBinder extends Binder{
        //サービスの取得
        WpNaviService getService(){
            return WpNaviService.this;
        }
    }

	void SetStatus( int iStatus ){
		int iPrevStat = m_iStatus;
		m_iStatus = iStatus;
		if( bDebug ) Log.d( "WpNavi", "Service status " + iPrevStat + "->" + iStatus );
		
		if( m_MsgHandler != null && iPrevStat != iStatus ){
			Message Msg = new Message();
			Msg.what	= MSG_CHG_STATE;
			Msg.arg1	= iPrevStat;
			m_MsgHandler.dispatchMessage( Msg );
		}
	}
	
	int GetStatus(){
		// サービス状態を返す
		// ナビリスタートから 2秒以内は RESTART を返す
		return (
			m_iStatus == STATUS_RUNNING &&
			( System.currentTimeMillis() - iRestartTime ) < ( 2000 + iWaitTime )
		) ? STATUS_RESTART : m_iStatus;
	}

    /*** ナビ起動 ***************************************************************/

	final void StartNavi(){
		if( bDebug ) Log.d( "WpNavi", "StartNavi:WP" + iCurWayPoint + ":" +
			WayPoint.GetLng( iCurWayPoint ) + "," +
			WayPoint.GetLat( iCurWayPoint )
		);

		SetStatus( STATUS_RUNNING );
		
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
		SetStatus( STATUS_IDLE );
		CancelNotification();
		StopLocationUpdate();
		stopSelf();
	}

	/*** GPS ハンドラ ***********************************************************/

	// 電波なしナビモード開始・終了
	boolean StartLocationUpdate(){
		if( GooglePlayServicesUtil.isGooglePlayServicesAvailable( this ) != ConnectionResult.SUCCESS ){
			return false;
		}
		
		if( m_GoogleApiClient == null ){
			m_GoogleApiClient = new GoogleApiClient.Builder( this )
				.addConnectionCallbacks( this )
				.addOnConnectionFailedListener( this )
				.addApi( LocationServices.API )
				.build();
		}
		
		if( !m_GoogleApiClient.isConnected()) m_GoogleApiClient.connect();
		
		return true;
	}
	
	// GPS 取得開始・終了
	@SuppressWarnings("static-access")
	void StartLocationUpdate2(){
		LocationRequest LocationRequest = new LocationRequest();
		
		LocationRequest.setInterval( 1000 );
		LocationRequest.setFastestInterval( 1000 );
		LocationRequest.setPriority( LocationRequest.PRIORITY_HIGH_ACCURACY );
		
		LocationServices.FusedLocationApi.requestLocationUpdates(
			m_GoogleApiClient, LocationRequest, this
		);
	}
	
	public void onConnected( Bundle arg0 ){
		if( bDebug ) Log.d( "WpNavi", "Service::onConnected" );
		StartLocationUpdate2();
	}

	void StopLocationUpdate(){
		if( m_GoogleApiClient != null ){
			if( m_GoogleApiClient.isConnected()){
				LocationServices.FusedLocationApi.removeLocationUpdates(
					m_GoogleApiClient, this
				);
			}
			m_GoogleApiClient.disconnect();
		}
	}
	
	public void onConnectionFailed( ConnectionResult arg0 ){
		if( bDebug ) Log.d( "WpNavi", "Service::onConnectionFailed" );
	}
	
	public void onDisconnected(){
		if( bDebug ) Log.d( "WpNavi", "Service::onDisconnected" );
	}
	
	@Override
	public void onConnectionSuspended( int arg0 ){
		if( bDebug ) Log.d( "WpNavi", "Service::onConnectionSuspended" );
	}
	
	private static int iDebugNavStartCnt = 0;

	@Override
	public void onLocationChanged( Location location ){
		if( bDebug ) Log.d( "WpNavi",
			"st:" + m_iStatus
			+ " d:" + (( int )WayPoint.Distance( iCurWayPoint, location.getLongitude(), location.getLatitude()))
			+ " GPS lon=" + location.getLongitude() + " lat=" + location.getLatitude()
		);
		
		m_Location	= location;
		
		// 経由地に近づいたらナビ起動
		boolean bStartNavi =
			(
				( bRestartTest && bDebug && ( iDebugNavStartCnt = ( iDebugNavStartCnt + 1 ) & 0xF ) == 0 ) ||
				WayPoint.InDistance( iNextDistance, iCurWayPoint, location.getLongitude(), location.getLatitude())
			) && (
				bReverseOrder ? --iCurWayPoint >= 0 : ++iCurWayPoint < WayPoint.Size()
			);
		
		if(( bStartNavi || m_iStatus == STATUS_NOSIG ) && IsNetworkAlive()){
			StartNavi();
			
		}else if( bStartNavi && m_iStatus == STATUS_RUNNING ){
			// STATUS_RUNNING で WP に到達した時に電波がない状態．
			// Google ナビを閉じて WpNavi を前面に出す．
			SetStatus( STATUS_NOSIG );
			CancelNotification();
			
			Intent intent = new Intent( Intent.ACTION_VIEW );
			intent.setClassName( "jp.dds.wpnavi", "jp.dds.wpnavi.WpNaviActivity" );
			intent.setFlags( Intent.FLAG_ACTIVITY_NEW_TASK );
			startActivity( intent );
			
		}else if( m_iStatus == STATUS_NOSIG && m_MsgHandler != null ){
			// 位置表示更新
			Message Msg = new Message();
			Msg.what	= MSG_UPDATE;
			m_MsgHandler.sendMessage( Msg );
		}
		
		if( iCurWayPoint == ( bReverseOrder ? 0 : WayPoint.Size() - 1 )) StopNavi();
	}
	
	// 電波状態取得
	private boolean IsNetworkAlive(){
		NetworkInfo Info = (( ConnectivityManager )getSystemService( CONNECTIVITY_SERVICE ))
			.getActiveNetworkInfo();
		
		//return false && Info != null && Info.isConnected();
		//return Info != null && Info.isConnected();
		return iCurWayPoint < 3;
	}
	
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
