package jp.dds.wpnavi;

import java.io.File;
import java.io.FileFilter;
import java.util.ArrayList;
import com.google.android.gms.ads.*;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.GoogleMap.OnMarkerClickListener;
import com.google.android.gms.maps.UiSettings;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.SupportMapFragment;

import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.app.DownloadManager.Query;
import android.app.DownloadManager.Request;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.drawable.ColorDrawable;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.preference.PreferenceManager;
import android.support.v7.app.ActionBar;
import android.support.v7.app.ActionBarActivity;
import android.util.Log;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;
import jp.dds.dds_lib.FileOpenDialog;

public class WpNaviActivity extends ActionBarActivity
	implements FileOpenDialog.FileOpenDialogListener {

	static final boolean bDebug	= BuildConfig.DEBUG;
	boolean m_bEnableAds	= true;
	private static final String m_strGMEUrl = "https://www.google.com/maps/d";
	private static final String m_strDownloadKmlName	= "/wpnavi.kml";
	private static final String m_strDownloadKmlNameTmp	= "/wpnavi.kml.tmp";

	private int	m_iCurWayPoint			= 0;
	private KmlManager	m_WayPoint		= new KmlManager();
	private SharedPreferences m_Pref	= null;
	private String	m_strKmlFile		= null;
	private boolean m_bDownloading		= false;
	private	boolean m_bQuitService		= false;
	private float m_fZoom;
	private float m_fNosigZoom;

	private GoogleMap m_Map;
	private ArrayList<Marker>	m_Markers = new ArrayList<Marker>();
	
	private LinearLayout m_LayoutAd;	// 広告表示用スペース
	private AdView m_adView;
	private int	m_iMagicNum		= 0;
	
	/*** Activity management ************************************************/

	@SuppressWarnings("unused")
	@SuppressLint( "InlinedApi" )
	public void onCreate( Bundle savedInstanceState ){
		if( bDebug ) Log.d( "WpNavi", "WpNavi::onCreate" );

		super.onCreate( savedInstanceState );
		
		// プリファレンス
		m_Pref = PreferenceManager.getDefaultSharedPreferences( this );
		
		if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB ){
			getWindow().requestFeature( Window.FEATURE_ACTION_BAR_OVERLAY );
		}
		setContentView( R.layout.main );
		
		// ActionBar オーバーレイ設定
		if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB ){
			ActionBar mActionBar = getSupportActionBar();
			mActionBar.setBackgroundDrawable( new ColorDrawable( 0x80000000 ));
		}
		
		DoIntent( getIntent());
		
		// 広告
		m_bEnableAds = !bDebug && m_Pref.getInt( "key_flag", 0 ) != 44298893;
		if( m_bEnableAds ){
			m_adView = new AdView( this );
			m_adView.setAdUnitId( "ca-app-pub-2092805559453853/9075326132" );
			m_adView.setAdSize( AdSize.SMART_BANNER );
			
			m_LayoutAd = ( LinearLayout )findViewById( R.id.LinearLayout1 );
			m_LayoutAd.addView( m_adView );
			
			AdRequest adRequest = new AdRequest.Builder().build();
			m_adView.loadAd( adRequest );
		}
		
		RegisterBroadcastReceiver();
		
		// 設定ロード
		m_iCurWayPoint	= m_Pref.getInt( "key_waypoint", 0 );
		m_strKmlFile	= m_Pref.getString( "key_kml_file", null );
		m_fZoom			= m_Pref.getFloat( "key_gmap_zoom", 1 );
		m_fNosigZoom	= m_Pref.getFloat( "key_nosig_zoom", 1 );
	}

	@Override
	protected void onResume(){
		if( bDebug ) Log.d( "WpNavi", "WpNavi::onResume" );
		super.onResume();
		if( m_bEnableAds ) m_adView.resume();	// 広告
		BindService();
	}

	@Override 
	public void onWindowFocusChanged( boolean hasFocus ){
		super.onWindowFocusChanged( hasFocus );
		
		if( bDebug ) Log.d( "WpNavi", "WpNavi::onWindowFocusChanged" );
		SetupMapIfNeeded();
	}
	
	@Override
	protected void onPause(){
		if( bDebug ) Log.d( "WpNavi", "WpNavi::onPause" );
		if( m_bEnableAds ) m_adView.pause();	// 広告
		super.onPause();
		
		// サービス停止
		int iStatus = mService != null ? mService.GetStatus() : WpNaviService.STATUS_IDLE;
		UnbindService();
		
		Editor ed = m_Pref.edit();
		
		// GMap カメラ位置保存
		if( m_Map != null ){
			CameraPosition cam = m_Map.getCameraPosition();
			
			if( iStatus != WpNaviService.STATUS_NOSIG ){
				m_fZoom = cam.zoom;
			}
			
			ed.putFloat( "key_gmap_lng", ( float )cam.target.longitude );
			ed.putFloat( "key_gmap_lat", ( float )cam.target.latitude );
			ed.putFloat( "key_gmap_zoom", m_fZoom );
			ed.putInt( "key_waypoint", m_iCurWayPoint );
			ed.putString( "key_kml_file", m_strKmlFile );
			ed.putFloat( "key_nosig_zoom", m_fNosigZoom );
		}
		
		if( m_iMagicNum == 44298893 ){
			ed.putInt( "key_flag", m_iMagicNum );
		}
		ed.commit();
	}

	public void onClickStartNavi( View v ){
		if( m_WayPoint.Size() == 0 ){
			Toast.makeText( this, R.string.text_KMLNotLoaded, Toast.LENGTH_LONG ).show();
			return;
		}
		
		if( mService == null ) return;
		
		if( mService.GetStatus() == WpNaviService.STATUS_NOSIG ){
			mService.StopNavi();
		}else{
			StartService();
		}
	}

	public void onClickPrevWp( View v ){
		int iNewWp = m_iCurWayPoint - 1;
		if( iNewWp < 0 ) iNewWp = m_WayPoint.Size() - 1;
		SetMoveCurWayPoint( iNewWp );
	}

	public void onClickNextWp( View v ){
		int iNewWp = m_iCurWayPoint + 1;
		if( iNewWp >= m_WayPoint.Size()) iNewWp = 0;
		SetMoveCurWayPoint( iNewWp );
	}

	@Override
	protected void onActivityResult( int requestCode, int resultCode, Intent data ){
		if( bDebug ) Log.d( "WpNavi", "WpNavi::onActivityResult" );
		//finish();
	}

	/*
	@Override
	protected void onStop(){
		if( bDebug ) Log.d( "WpNavi", "WpNavi::onStop" );
		super.onStop();
	}
	*/
	
	@Override
	protected void onDestroy(){
		if( bDebug ) Log.d( "WpNavi", "WpNavi::onDestroy" );
		
		StopService();
		
		if( m_bEnableAds ) m_adView.destroy();	// 広告
		UnregisterBroadcastReceiver();
		
		super.onDestroy();
	}

	// 画面回転時の destroy 防止
	@Override
	public void onConfigurationChanged( Configuration newConfig ){
		super.onConfigurationChanged( newConfig );
		if( bDebug ) Log.d( "WpNavi", "WpNavi::onConfigurationChanged" );
	}
	
	/*** Google Maps ********************************************************/

	private void SetupMapIfNeeded(){
		// Do a null check to confirm that we have not already instantiated the map.
		if( m_Map != null ) return;
		
		// Try to obtain the map from the SupportMapFragment.
		m_Map = (( SupportMapFragment )getSupportFragmentManager().findFragmentById( R.id.map )).getMap();
		if( m_Map == null ) return;
		
		m_Map.setMyLocationEnabled( true );

		UiSettings ui = m_Map.getUiSettings();

		// Keep the UI Settings state in sync with the checkboxes.
		m_Map.setMyLocationEnabled( true );
		
		ui.setZoomControlsEnabled( true );
		//mUiSettings.setCompassEnabled( true );
		ui.setMyLocationButtonEnabled( true );
		ui.setScrollGesturesEnabled( true );
		ui.setZoomGesturesEnabled( true );
		//mUiSettings.setTiltGesturesEnabled( true );
		//mUiSettings.setRotateGesturesEnabled( true );
		
		// 渋滞情報
		m_Map.setTrafficEnabled( m_Pref.getBoolean( "key_traffic_info", false ));
		
		// Map 移動
		CameraPosition cameraPos = new CameraPosition.Builder()
			.target( new LatLng( m_Pref.getFloat( "key_gmap_lat", 0f ), m_Pref.getFloat( "key_gmap_lng", 0f )))
			.zoom( m_fZoom )
			.bearing( 0 )
			.build();
		m_Map.moveCamera( CameraUpdateFactory.newCameraPosition( cameraPos ));
		
		// マーカークリックリスナー登録
		m_Map.setOnMarkerClickListener( new OnMarkerClickListener(){
			@Override
			public boolean onMarkerClick( Marker marker ){
				SetCurWayPoint( Integer.parseInt( marker.getTitle().toString().substring( 2 )) - 1 );
				return false;
			}
		});
		
		TypedValue tv = new TypedValue();
		if( getTheme().resolveAttribute(android.R.attr.actionBarSize, tv, true )){
		    m_Map.setPadding( 0,
		    	TypedValue.complexToDimensionPixelSize( tv.data,getResources().getDisplayMetrics()),
		    	0,
		    	findViewById( R.id.buttonPrevWp ).getHeight()
		    );
		}
		
		if( m_strKmlFile != null ) LoadKML( m_strKmlFile, m_iCurWayPoint );
	}

	final void SetCurWayPoint( int iNewWp ){
		if( m_Map != null && m_Markers.size() != 0 ){
			// 元 CurWP のアイコンを blue にする
			m_Markers.get( m_iCurWayPoint ).setIcon(
				BitmapDescriptorFactory.defaultMarker( BitmapDescriptorFactory.HUE_BLUE )
			);

			m_Markers.get( iNewWp ).setIcon(
				BitmapDescriptorFactory.defaultMarker( BitmapDescriptorFactory.HUE_RED )
			);
		}
		m_iCurWayPoint = iNewWp;
	}

	final void SetMoveCurWayPoint( int iNewWp ){
		if( m_Map != null && m_Markers.size() != 0 ){
			SetCurWayPoint( iNewWp );
			m_Markers.get( iNewWp ).showInfoWindow();

			m_Map.animateCamera( CameraUpdateFactory.newLatLng( m_Markers.get( iNewWp ).getPosition()));
		}
	}

	/*** Load KML ***********************************************************/

	public boolean LoadKML( String strKmlFile, int iWayPoint ){
		
		KmlManager.KmlInfo Info = m_WayPoint.LoadKML(
			strKmlFile, m_Pref.getInt( "key_NextDistance", 50 )
		);
		
		if( Info.m_iErrorCode != 0 ){
			Toast.makeText( this, Info.m_iErrorCode, Toast.LENGTH_LONG ).show();
			return false;
		}
		
		m_Map.clear();
		m_Markers.clear();
		m_iCurWayPoint = 0;
		m_strKmlFile = strKmlFile;
		
		// タイトル設定
		if( Info.m_strTitle != null ){
			setTitle( Info.m_strTitle );
			
			// 広告 OFF マジック #
			try{
				m_iMagicNum = Integer.parseInt( Info.m_strTitle );
			}catch( Exception e ){
				m_iMagicNum = 0;
			}
		}else{
			setTitle( R.string.app_name );
		}
		
		// WP を Map に追加
		for( int i = 0; i < m_WayPoint.Size(); ++i ){
			MarkerOptions MakerOpt = new MarkerOptions();
			MakerOpt.position( m_WayPoint.GetPoint( i ));
			MakerOpt.title( String.format( "WP%d", i + 1 ));
			MakerOpt.icon( BitmapDescriptorFactory.defaultMarker( BitmapDescriptorFactory.HUE_BLUE ));
			//MakerOpt.snippet( location.toString());
			m_Markers.add( m_Map.addMarker( MakerOpt ));
		}

		float fDipScale = getApplicationContext().getResources().getDisplayMetrics().density;
		
		// Line を Map に追加
		Info.m_Polyline.color( 0xFF1166FF );
		Info.m_Polyline.width(( int )( 6 * fDipScale ));
		m_Map.addPolyline( Info.m_Polyline );

		SetCurWayPoint(
			iWayPoint >= 0 ? iWayPoint :
			m_Pref.getBoolean( "key_ReverseOrder", false ) ? m_WayPoint.Size() - 1 : 0
		);
		
		// ルートが 180W をまたいでいたら，補正
		if( Info.m_dMaxLng - Info.m_dMinLng > 180 ){
			double tmp = Info.m_dMaxLng;
			Info.m_dMaxLng = Info.m_dMinLng;
			Info.m_dMinLng = tmp;
		}
		
		// ルート全体に移動
		m_Map.moveCamera(
			CameraUpdateFactory.newLatLngBounds(
				LatLngBounds.builder()
					.include( new LatLng( Info.m_dMaxLat, Info.m_dMaxLng ))
					.include( new LatLng( Info.m_dMinLat, Info.m_dMinLng ))
					.build(),
				( int )( 16 * fDipScale )	// padding
			)
		);
		
		return true;
	}
	
	/*** Option menu ********************************************************/

	@Override
	public boolean onCreateOptionsMenu( Menu menu ){
		super.onCreateOptionsMenu( menu );
		MenuInflater inflater = getMenuInflater();
		inflater.inflate( R.menu.wp_navi, menu );
		return true;
	}

	@Override
	public boolean onOptionsItemSelected( MenuItem item ){
		switch( item.getItemId()){
			case R.id.itemLoadKML:
				FileOpenDialog fod = new FileOpenDialog(
					WpNaviActivity.this, this, FileOpenDialog.MODE_FILE,
					new FileFilter(){
						public boolean accept( File pathname ){
							return
								pathname.getName().endsWith( ".kml" ) ||
								pathname.getName().endsWith( ".kmz" ) ||
								pathname.getName().endsWith( ".xml" );
						}
					}
				);
				fod.openDirectory( m_strKmlFile );
				return true;

			case R.id.itemOpenGME: {
				Intent intent = new Intent( Intent.ACTION_VIEW,	Uri.parse( m_strGMEUrl + "/?authuser=0&action=open" ));
				intent.setFlags( Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP );
				startActivity( intent );
				return true;
			}
				
			case R.id.itemSetting: {
				Intent intent = new Intent( WpNaviActivity.this, WpNaviPreference.class );
				startActivityForResult( intent, 0 );
				return true;
			}
			
			case R.id.itemHelp: {
				Intent intent = new Intent( Intent.ACTION_VIEW,	Uri.parse( getString( R.string.URL_OnlineManual )));
				intent.setFlags( Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP );
				startActivity( intent );
				return true;
			}
		}
		return false;
	}

	public void onFileSelected( File file ){
		LoadKML( file.getAbsolutePath(), -1 );
	}

	/*** GME URL intent ****************************************************/

	@Override
	protected void onNewIntent( Intent intent ){
		if( bDebug ) Log.d( "WpNavi", "WpNavi::onNewIntent" );
		super.onNewIntent( intent );
		DoIntent( intent );
	}

	final boolean DoIntent( Intent intent ){
		if( intent == null ) return false;
		if( bDebug ) Log.d( "WpNavi", "DoIntent:Action:" + intent.getAction());
		
		// notification から呼ばれた
		if( intent.getBooleanExtra( "quit_service", false )){
			if( bDebug ) Log.d( "WpNavi", "DoIntent:Killed by notification" );
			m_bQuitService = true;
			return true;
		}
		
		// URL フィルタに引っかかった
		String strUrl = intent.getDataString();
		if( strUrl != null ) return DownloadURL( strUrl );
		
		return false;
	}
	
	final boolean DownloadURL( String strUrl ){
		/** リンク先のURLを取得する。 */
		String strDstFile = WpNaviActivity.this.getExternalFilesDir( Environment.DIRECTORY_DOWNLOADS ) + m_strDownloadKmlNameTmp;
		
		try{
			( new File( strDstFile )).delete();
		}catch( Exception e ){}
		
		if( bDebug ) Log.d( "WpNavi", "WpNavi::DoIntent:editUrl:" + strUrl );

		// mid を取得
		String strMid = strUrl.replaceFirst( ".*mid=", "" ).replaceFirst( "&.*", "" );
		if( bDebug ) Log.d( "WpNavi", "WpNavi::DoIntent:editUrl:" + strMid );
		
		Uri.Builder uriBuilder = Uri.parse( m_strGMEUrl + "/kml" ).buildUpon();
		uriBuilder.appendQueryParameter( "authuser", "0" );
		uriBuilder.appendQueryParameter( "mid", strMid );

		if( bDebug ) Log.d( "WpNavi", "WpNavi::DoIntent:kmlUrl:" + uriBuilder );
		
		Request request = new Request( uriBuilder.build());
		request.setDestinationInExternalFilesDir( WpNaviActivity.this, Environment.DIRECTORY_DOWNLOADS, m_strDownloadKmlNameTmp );
		request.setVisibleInDownloadsUi( false );
		request.setAllowedNetworkTypes( DownloadManager.Request.NETWORK_MOBILE | DownloadManager.Request.NETWORK_WIFI );
		//request.setMimeType( "application/vnd.google-earth.kml+xml" );
		
		m_bDownloading = true;
		(( DownloadManager )getSystemService( DOWNLOAD_SERVICE )).enqueue( request );
		
		return true;
	}
	
	BroadcastReceiver mReceiver = new BroadcastReceiver(){
		@Override
		public void onReceive( Context context, Intent intent ){
			String action = intent.getAction();
			if( DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals( action )){

				long id = intent.getLongExtra( DownloadManager.EXTRA_DOWNLOAD_ID, -1 );

				Query query = new Query();
				query.setFilterById( id );
				Cursor cursor = (( DownloadManager )getSystemService( DOWNLOAD_SERVICE )).query( query );

				if( cursor.moveToFirst() && m_bDownloading ){
					int status = cursor.getInt( cursor.getColumnIndex( DownloadManager.COLUMN_STATUS ));
					int reason = cursor.getInt( cursor.getColumnIndex( DownloadManager.COLUMN_REASON ));
					if( bDebug ){
						Log.d( "WpNavi", "BBRcv:status=" + Integer.toString( status ));
						Log.d( "WpNavi", "BBRcf:reason=" + Integer.toString( reason ));
					}
					
					if( status == DownloadManager.STATUS_SUCCESSFUL ){
						m_bDownloading = false;
						
						// ダウンロードに成功した場合
						String strTmpFile = WpNaviActivity.this.getExternalFilesDir( Environment.DIRECTORY_DOWNLOADS ) + m_strDownloadKmlNameTmp;
						String strKmlFile = WpNaviActivity.this.getExternalFilesDir( Environment.DIRECTORY_DOWNLOADS ) + m_strDownloadKmlName;
						
						File fileKml = new File( strKmlFile );
						try{ fileKml.delete(); }catch( Exception e ){}
						try{ ( new File( strTmpFile )).renameTo( fileKml ); }catch( Exception e ){}
						LoadKML( strKmlFile, -1 );
					}else{
						// ダウンロードに失敗した場合
						Toast.makeText( WpNaviActivity.this, R.string.text_DownloadFailed, Toast.LENGTH_LONG ).show();
					}
				}
				cursor.close();
			}
		}
	};
		
	final void RegisterBroadcastReceiver(){
		registerReceiver( mReceiver, new IntentFilter( DownloadManager.ACTION_DOWNLOAD_COMPLETE ));
	}
	
	final void UnregisterBroadcastReceiver(){
		if( mReceiver != null ) unregisterReceiver( mReceiver );
		mReceiver = null;
	}
	
	/*** Service ************************************************************/

	//取得したServiceの保存
	private WpNaviService mService = null;

	private ServiceConnection mConnection = new ServiceConnection(){
		@Override
		public void onServiceConnected( ComponentName className, IBinder service ){

			// サービスとの接続確立時に呼び出される
			if( bDebug ) Log.d( "WpNavi", "WpNavi::onServiceConnected" );

			// サービスにはIBinder経由で#getService()してダイレクトにアクセス可能
			mService = (( WpNaviService.WpNaviServiceLocalBinder )service ).getService();
			
			if( mService.GetStatus() != WpNaviService.STATUS_IDLE ){
				SetCurWayPoint( mService.iCurWayPoint );
			}
			
			// メッセージハンドラ
			mService.m_MsgHandler	= new Handler(){
				public void handleMessage( Message Msg ){
					if( mService != null ) switch( Msg.what ){
					  case WpNaviService.MSG_UPDATE_WP:
						SetCurWayPoint( mService.iCurWayPoint );
						
					  case WpNaviService.MSG_UPDATE:
						OnLocationChanged( mService.m_Location );
						break;
						
					  case WpNaviService.MSG_CHG_STATE:
						OnStateChanged( Msg.arg1 );
					}
				}
			};
			
			// WpNavi 起動時に，以下の条件でサービスを止める
			// ・m_bQuitService (Notification から kill された)
			// ・STATUS_RUNNING (↑だけで，要らない気はする)
			//
			// STATUS_RESTART は，ナビリスタート時に Google ナビを kill すると
			// WpNavi に一瞬返ってくるのでその対策．
			int iStatus = mService.GetStatus();
			if( m_bQuitService || iStatus == WpNaviService.STATUS_RUNNING ){
				mService.StopNavi();
				if( bDebug ) Log.d( "WpNavi", "Service stopped:" + iStatus );
				m_bQuitService = false;
				
			}else if( iStatus == WpNaviService.STATUS_NOSIG ){
				EnterNosigUI();
			}
			
			if( bDebug ) Log.d( "WpNavi", "Service's stat=" + iStatus + " WP=" + m_iCurWayPoint );
		}

		@Override
		public void onServiceDisconnected( ComponentName className ){
			if( bDebug ) Log.d( "WpNavi", "WpNavi::onServiceDisconnected" );
			// サービスとの切断( 異常系処理 )
			// プロセスのクラッシュなど意図しないサービスの切断が発生した場合に呼ばれる。
			mService = null;
		}
	};

	final void StartService(){
		Intent intent = new Intent( this, WpNaviService.class );

		// 設定値を Service に設定
		intent.putExtra( "WayPoint", m_WayPoint.Points );
		mService.iCurWayPoint	= m_iCurWayPoint;
		mService.iNextDistance	= m_Pref.getInt( "key_NextDistance", 50 );
		mService.iWaitTime		= m_Pref.getInt( "key_WaitTime", 60 ) * 100;
		mService.bKillByRoot	= m_Pref.getBoolean( "key_kill_by_root", false );
		mService.bReverseOrder	= m_Pref.getBoolean( "key_ReverseOrder", false );
		
		startService( intent );
	}

	final void StopService(){
		stopService( new Intent( this, WpNaviService.class ));
	}

	final void BindService(){
		//サービスとの接続を確立する。明示的にServiceを指定
		//( 特定のサービスを指定する必要がある。他のアプリケーションから知ることができない = ローカルサービス )
		bindService( new Intent( this, WpNaviService.class ), mConnection, Context.BIND_AUTO_CREATE );
	}

	final void UnbindService(){
		if( mService != null ){
			// コネクションの解除
			unbindService( mConnection );
			mService = null;
		}
	}
	
	/*** 無電波モード *******************************************************/
	
	// 電波なしナビモード開始・終了
	void EnterNosigUI(){
		getWindow().addFlags( WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON );
		
		Button btn = ( Button )findViewById( R.id.buttonStartNavi );
		btn.setText(( String )getText( R.string.button_stop_navi ));
		
		if( m_Map != null ){
			CameraPosition camOld = m_Map.getCameraPosition();
			m_fZoom = camOld.zoom;
			
			CameraPosition camNew = new CameraPosition.Builder()
				.target( camOld.target )
				.zoom( m_fNosigZoom )
				.tilt( 75 )
				.build();
			m_Map.moveCamera( CameraUpdateFactory.newCameraPosition( camNew ));
		}
		
		if( bDebug ) Log.d( "WpNavi", "EnterNosigUI::z:" + m_fZoom + " nz:" + m_fNosigZoom );
	}
	
	void ExitNosigUI(){
		getWindow().clearFlags( WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON );
		
		if( m_Map != null ){
			CameraPosition camOld = m_Map.getCameraPosition();
			
			CameraPosition camNew = new CameraPosition.Builder()
				.target( camOld.target )
				.zoom( m_fZoom )
				.tilt( 0 )
				.bearing( 0 )
				.build();
			m_Map.moveCamera( CameraUpdateFactory.newCameraPosition( camNew ));
		}
		
		if( bDebug ) Log.d( "WpNavi", "ExitNosigUI::z:" + m_fZoom + " nz:" + m_fNosigZoom );
		
		Button btn = ( Button )findViewById( R.id.buttonStartNavi );
		btn.setText(( String )getText( R.string.button_start_navi ));
	}
	
	// 一定時間ごと地図位置更新
	void OnLocationChanged( Location location ){
		if( m_Map != null ){
			CameraPosition camOld = m_Map.getCameraPosition();
			
			m_fNosigZoom = camOld.zoom;
			
			CameraPosition camNew = new CameraPosition.Builder()
				.target( new LatLng( location.getLatitude(), location.getLongitude()))
				.zoom( camOld.zoom )
				.tilt( 75 )
				.bearing( location.getBearing())
				.build();
			m_Map.animateCamera( CameraUpdateFactory.newCameraPosition( camNew ));
		}
	}
	
	// サービスステート変更
	void OnStateChanged( int iPrevState ){
		if( mService == null ) return;
		
		if( mService.GetStatus() == WpNaviService.STATUS_NOSIG ){
			EnterNosigUI();
		}else if( iPrevState == WpNaviService.STATUS_NOSIG ){
			ExitNosigUI();
		}
	}
}
