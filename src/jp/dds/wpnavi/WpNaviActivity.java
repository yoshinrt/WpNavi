package jp.dds.wpnavi;

import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;

import org.xmlpull.v1.XmlPullParser;

import com.google.android.gms.ads.*;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.GoogleMap.OnMarkerClickListener;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.UiSettings;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.PolylineOptions;

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
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.support.v7.app.ActionBar;
import android.support.v7.app.ActionBarActivity;
import android.util.Log;
import android.util.TypedValue;
import android.util.Xml;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.Toast;
import jp.dds.dds_lib.FileOpenDialog;

public class WpNaviActivity extends ActionBarActivity implements FileOpenDialog.FileOpenDialogListener{

	static final boolean bDebug	= BuildConfig.DEBUG;
	static boolean bEnableAds	= true;
	private static final String m_strGMEUrl = "https://mapsengine.google.com/map";
	private static final String m_strDownloadKmlName	= "/wpnavi.kml";
	private static final String m_strDownloadKmlNameTmp	= "/wpnavi.kml.tmp";

	private int	iCurWayPoint		= 0;
	private Coordinate	WayPoint	= new Coordinate();
	private SharedPreferences Pref	= null;
	private String	m_strKmlFile	= null;
	private boolean m_bDownloading	= false;
	private	boolean m_bQuitService	= false;

	private ArrayList<Marker>	Markers = new ArrayList<Marker>();
	
	private LinearLayout layout_ad;	//広告表示用スペース
	private AdView adView;
	private int	m_iMagicNum		= 0;

	/*** Activity management ************************************************/

	public void onCreate( Bundle savedInstanceState ){
		if( bDebug ) Log.d( "WpNavi", "WpNavi::onCreate" );

		super.onCreate( savedInstanceState );
		getWindow().requestFeature( Window.FEATURE_ACTION_BAR_OVERLAY );
		setContentView( R.layout.main );
		
		// ActionBar オーバーレイ設定
		ActionBar mActionBar = getSupportActionBar();
		mActionBar.setBackgroundDrawable( new ColorDrawable( 0x80000000 ));

		setUpMapIfNeeded();

		// プリファレンス
		Pref = PreferenceManager.getDefaultSharedPreferences( this );
		GMEIntent( getIntent());
		
		// 広告
		bEnableAds = Pref.getInt( "key_flag", 0 ) != 44298893;
		if( bEnableAds ){
			adView = new AdView( this );
			adView.setAdUnitId( "ca-app-pub-2092805559453853/9075326132" );
			adView.setAdSize( AdSize.SMART_BANNER );
			
			layout_ad = ( LinearLayout )findViewById( R.id.LinearLayout1 );
			layout_ad.addView( adView );
			
			AdRequest adRequest = new AdRequest.Builder().build();
			adView.loadAd( adRequest );
		}
		
		RegisterBroadcastReceiver();
	}

	@Override
	protected void onResume(){
		if( bDebug ) Log.d( "WpNavi", "WpNavi::onResume" );
		super.onResume();
		if( bEnableAds ) adView.resume();	// 広告
		BindService();
		
		if( mMap != null ){
			// マーカークリックリスナー登録
			mMap.setOnMarkerClickListener( new OnMarkerClickListener(){
				@Override
				public boolean onMarkerClick( Marker marker ){
					SetCurWayPoint( Integer.parseInt( marker.getTitle().toString().substring( 2 )) - 1 );

					return false;
				}
			});

			// KML ロード
			m_strKmlFile = Pref.getString( "key_kml_file", null );

			(( SupportMapFragment )getSupportFragmentManager().findFragmentById( R.id.map )).getView().post( new Runnable(){
				@Override
				public void run(){
					TypedValue tv = new TypedValue();
					if( getTheme().resolveAttribute(android.R.attr.actionBarSize, tv, true )){
					    mMap.setPadding( 0,
					    	TypedValue.complexToDimensionPixelSize( tv.data,getResources().getDisplayMetrics()),
					    	0,
					    	findViewById( R.id.buttonPrevWp ).getHeight()
					    );
					}
					
					if( m_strKmlFile != null && WayPoint.Size() == 0 ){
						LoadKML( m_strKmlFile );
					}else{
						// Map 移動
						CameraPosition cameraPos = new CameraPosition.Builder()
							.target( new LatLng( Pref.getFloat( "key_gmap_lat", 0f ), Pref.getFloat( "key_gmap_lng", 0f )))
							.zoom( Pref.getFloat( "key_gmap_zoom", 0 ))
							.bearing( 0 )
							.build();
						mMap.moveCamera( CameraUpdateFactory.newCameraPosition( cameraPos ));
					}
				}
			});
		}
	}

	@Override
	protected void onPause(){
		if( bDebug ) Log.d( "WpNavi", "WpNavi::onPause" );
		if( bEnableAds ) adView.pause();	// 広告
		super.onPause();
		UnbindService();

		// GMap カメラ位置保存
		CameraPosition cam = mMap.getCameraPosition();

		Editor ed = Pref.edit();
		ed.putFloat( "key_gmap_lng", ( float )cam.target.longitude );
		ed.putFloat( "key_gmap_lat", ( float )cam.target.latitude );
		ed.putFloat( "key_gmap_zoom", cam.zoom );
		ed.putString( "key_kml_file", m_strKmlFile );
		
		if( m_iMagicNum == 44298893 ){
			ed.putInt( "key_flag", m_iMagicNum );
		}
		ed.commit();
	}

	public void onClickStartNavi( View v ){
		if( WayPoint.Size() == 0 ){
			Toast.makeText( this, R.string.text_KMLNotLoaded, Toast.LENGTH_LONG ).show();
			return;
		}

		// サービス開始
		StartService();
	}

	public void onClickPrevWp( View v ){
		int iNewWp = iCurWayPoint - 1;
		if( iNewWp < 0 ) iNewWp = WayPoint.Size() - 1;
		SetMoveCurWayPoint( iNewWp );
	}

	public void onClickNextWp( View v ){
		int iNewWp = iCurWayPoint + 1;
		if( iNewWp >= WayPoint.Size()) iNewWp = 0;
		SetMoveCurWayPoint( iNewWp );
	}

	@Override
	protected void onActivityResult( int requestCode, int resultCode, Intent data ){
		if( bDebug ) Log.d( "WpNavi", "WpNavi::onActivityResult" );
		//finish();
	}

	@Override
	protected void onDestroy(){
		if( bDebug ) Log.d( "WpNavi", "WpNavi::onDestroy" );
		if( bEnableAds ) adView.destroy();	// 広告
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

	private GoogleMap mMap;
	private void setUpMapIfNeeded(){
		UiSettings ui;
	
		// Do a null check to confirm that we have not already instantiated the map.
		if( mMap == null ){
			// Try to obtain the map from the SupportMapFragment.
			mMap = (( SupportMapFragment )getSupportFragmentManager().findFragmentById( R.id.map )).getMap();
			// Check if we were successful in obtaining the map.
			if( mMap != null ){
				mMap.setMyLocationEnabled( true );

				ui = mMap.getUiSettings();

				// Keep the UI Settings state in sync with the checkboxes.
				mMap.setMyLocationEnabled( true );
				
 				ui.setZoomControlsEnabled( true );
				//mUiSettings.setCompassEnabled( true );
				ui.setMyLocationButtonEnabled( true );
				ui.setScrollGesturesEnabled( true );
				ui.setZoomGesturesEnabled( true );
				//mUiSettings.setTiltGesturesEnabled( true );
				//mUiSettings.setRotateGesturesEnabled( true );
			}
		}
	}

	final void SetCurWayPoint( int iNewWp ){
		if( mMap != null && Markers.size() != 0 ){
			// 元 CurWP のアイコンを blue にする
			Markers.get( iCurWayPoint ).setIcon(
				BitmapDescriptorFactory.defaultMarker( BitmapDescriptorFactory.HUE_BLUE )
			);

			Markers.get( iNewWp ).setIcon(
				BitmapDescriptorFactory.defaultMarker( BitmapDescriptorFactory.HUE_RED )
			);
		}
		iCurWayPoint = iNewWp;
	}

	final void SetMoveCurWayPoint( int iNewWp ){
		if( mMap != null && Markers.size() != 0 ){
			SetCurWayPoint( iNewWp );
			Markers.get( iNewWp ).showInfoWindow();

			CameraPosition camOld = mMap.getCameraPosition();
			CameraPosition camNew = new CameraPosition.Builder()
				.target( Markers.get( iNewWp ).getPosition())
				.zoom( camOld.zoom )
				.build();
			mMap.animateCamera( CameraUpdateFactory.newCameraPosition( camNew ));
		}
	}

	/*** Load KML ***********************************************************/

	private static final int	KML_NONE		= 0;
	private static final int	KML_POINT		= 1 << 0;
	private static final int	KML_LINESTRING	= 1 << 1;
	private static final int	KML_COORDINATES	= 1 << 2;

	public boolean LoadKML( String strKmlFile ){
		int		iState;
		String	strTitle = null;
		
		if( mMap == null || strKmlFile == null ) return false;

		// KMK を開く
		FileInputStream fsIn;
		try{
			fsIn = new FileInputStream( strKmlFile );
		}catch( FileNotFoundException e ){
			//Toast.makeText( this, R.string.text_FileNotFound, Toast.LENGTH_LONG ).show();
			return false;
		}

		XmlPullParser xpp = Xml.newPullParser();

		WayPoint.Clear();	// WP 等のクリア
		iState = KML_NONE;

		double[] Point = new double[ 6 ];
		Point[ 2 ] = Point[ 3 ] = 1000;		// min Lng, Lat
		Point[ 4 ] = Point[ 5 ] = -1000;	// max Lng, Lat
		
		PolylineOptions PolyLineOpt = new PolylineOptions();
		int iMinDistance = Pref.getInt( "key_NextDistance", 50 );

		try{
			xpp.setInput( fsIn, "UTF-8" );

			// パース
			String str	= "";

			for( int iType = xpp.getEventType(); iType != XmlPullParser.END_DOCUMENT;
				iType = xpp.next()){
				switch( iType ){
				case XmlPullParser.START_TAG: // 開始タグ
					str = xpp.getName();

					if( str.equals( "LineString" )){
						iState |= KML_LINESTRING;
					}else if( str.equals( "Point" )){
						iState |= KML_POINT;
					}else if( str.equals( "coordinates" )){
						iState |= KML_COORDINATES;
					}else if( strTitle == null && str.equals( "name" )){
						strTitle = xpp.nextText();
					}

					//Log.d( "WpNavi", "Tag:" + str );
					break;

				case XmlPullParser.TEXT: // タグの内容
					if(( iState & KML_COORDINATES ) != 0 ){
						str = xpp.getText();

						if(( iState & KML_POINT ) != 0 ){
							// 経由地
							ParseCoordinate( str, Point );
							if(
								WayPoint.Size() == 0 ||
								WayPoint.Distance( WayPoint.Size() - 1, Point[ 0 ], Point[ 1 ] ) >=
								iMinDistance
							){
								WayPoint.Add( Point[ 0 ], Point[ 1 ] );
							}
						}else if(( iState & KML_LINESTRING ) != 0 ){
							// ルート
							int c1 = 0, c2;
							do{
								// 空白のサーチ
								for( c2 = c1; c2 < str.length(); ++c2 ){
									if( str.charAt( c2 ) <= ' ' ) break;
								}

								if( c1 != c2 ){
									ParseCoordinate( str.substring( c1, c2 ), Point );
									PolyLineOpt.add( new LatLng( Point[ 1 ], Point[ 0 ] ));
								}
								c1 = c2 + 1;
							}while( c1 < str.length());
						}

						//Log.d( "WpNavi", "Val:" + str );
						// 空白で取得したものは全て処理対象外とする
					}
					break;

				case XmlPullParser.END_TAG: // 終了タグ
					str = xpp.getName();
					//Log.d( "WpNavi", "Tag/:" + str );
					if( str.equals( "LineString" )){
						iState &= ~KML_LINESTRING;
					}else if( str.equals( "Point" )){
						iState &= ~KML_POINT;
					}else if( str.equals( "coordinates" )){
						iState &= ~KML_COORDINATES;
					}
					break;
				}
			}
		}catch( Exception e ){
			Toast.makeText( this, R.string.text_InvalidKMLFormat, Toast.LENGTH_LONG ).show();
			// e.printStackTrace();
			try{ fsIn.close(); }catch( IOException e2 ){}
			return false;
		}

		// close
		try{ fsIn.close(); }catch( IOException e ){}

		// 一応数チェック
		if( WayPoint.Size() == 0 ){
			Toast.makeText( this, R.string.text_InvalidKMLFormat, Toast.LENGTH_LONG ).show();
			return false;
		}

		// ここまで来たらロード成功

		mMap.clear();
		Markers.clear();
		iCurWayPoint = 0;
		m_strKmlFile = strKmlFile;
		
		// タイトル設定
		if( strTitle != null ){
			setTitle( strTitle );
			
			// 広告 OFF マジック #
			try{
				m_iMagicNum = Integer.parseInt( strTitle );
			}catch( Exception e ){
				m_iMagicNum = 0;
			}
		}else{
			setTitle( R.string.app_name );
		}
		
		// WP を Map に追加
		for( int i = 0; i < WayPoint.Size(); ++i ){
			MarkerOptions MakerOpt = new MarkerOptions();
			MakerOpt.position( WayPoint.GetPoint( i ));
			MakerOpt.title( String.format( "WP%d", i + 1 ));
			MakerOpt.icon( BitmapDescriptorFactory.defaultMarker( BitmapDescriptorFactory.HUE_BLUE ));
			//MakerOpt.snippet( location.toString());
			Markers.add( mMap.addMarker( MakerOpt ));
		}

		// Line を Map に追加
		PolyLineOpt.color( 0xFF1166FF );
		PolyLineOpt.width( 6 );
		mMap.addPolyline( PolyLineOpt );

		SetCurWayPoint( 0 );
		
		// ルートが 180W をまたいでいたら，補正
		if( Point[ 4 ] - Point[ 2 ] > 180 ){
			double tmp = Point[ 4 ];
			Point[ 4 ] = Point[ 2 ];
			Point[ 2 ] = tmp;
		}
		
		// ルート全体に移動
		mMap.animateCamera(
			CameraUpdateFactory.newLatLngBounds(
				LatLngBounds.builder()
					.include( new LatLng( Point[ 5 ], Point[ 4 ] ))
					.include( new LatLng( Point[ 3 ], Point[ 2 ] ))
					.build(),
				64	// padding
			)
		);
		
		return true;
	}
	
	void ParseCoordinate( String str, double Point[] ){
		int c1, c2;
		if(
			( c1 = str.indexOf( ',' )) >= 0 &&
			( c2 = str.indexOf( ',', c1 + 1 )) >= 0
		){
			Point[ 0 ] = Double.parseDouble( str.substring( 0, c1 ));
			Point[ 1 ] = Double.parseDouble( str.substring( c1 + 1, c2 ));
			
			if( Point[ 2 ] > Point[ 0 ] ) Point[ 2 ] = Point[ 0 ];
			if( Point[ 4 ] < Point[ 0 ] ) Point[ 4 ] = Point[ 0 ];
			if( Point[ 3 ] > Point[ 1 ] ) Point[ 3 ] = Point[ 1 ];
			if( Point[ 5 ] < Point[ 1 ] ) Point[ 5 ] = Point[ 1 ];
		}
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
							return !pathname.getName().startsWith( "." ) && (
								pathname.isDirectory() ||
								pathname.getName().endsWith( ".kml" ) ||
								pathname.getName().endsWith( ".xml" )
							);
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
				
			case R.id.itemSetting:
				Intent intent = new Intent( WpNaviActivity.this, WpNaviPreference.class );
				startActivityForResult( intent, 0 );
				return true;
		}
		return false;
	}

	public void onFileSelected( File file ){
		LoadKML( file.getAbsolutePath());
	}

	/*** GME URL intent ****************************************************/

	@Override
	protected void onNewIntent( Intent intent ){
		if( bDebug ) Log.d( "WpNavi", "WpNavi::onNewIntent" );
		super.onNewIntent( intent );
		GMEIntent( intent );
	}

	final boolean GMEIntent( Intent intent ){
		if( intent == null ) return false;
		if( bDebug ) Log.d( "WpNavi", "GMEIntent:Action:" + intent.getAction());
		
		// notification から呼ばれた
		if( intent.getBooleanExtra( "quit_service", false )){
			if( bDebug ) Log.d( "WpNavi", "GMEIntent:Killed by notification" );
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
		
		if( bDebug ) Log.d( "WpNavi", "WpNavi::GMEIntent:editUrl:" + strUrl );

		// mid を取得
		String strMid = strUrl.replaceFirst( ".*mid=", "" ).replaceFirst( "&.*", "" );
		if( bDebug ) Log.d( "WpNavi", "WpNavi::GMEIntent:editUrl:" + strMid );
		
		Uri.Builder uriBuilder = Uri.parse( m_strGMEUrl + "/kml" ).buildUpon();
		uriBuilder.appendQueryParameter( "authuser", "0" );
		uriBuilder.appendQueryParameter( "mid", strMid );

		if( bDebug ) Log.d( "WpNavi", "WpNavi::GMEIntent:kmlUrl:" + uriBuilder );
		
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
						LoadKML( strKmlFile );
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
	private boolean mIsBound;

	private ServiceConnection mConnection = new ServiceConnection(){
		@Override
		public void onServiceConnected( ComponentName className, IBinder service ){

			// サービスとの接続確立時に呼び出される
			if( bDebug ) Log.d( "WpNavi", "WpNavi::onServiceConnected" );

			// サービスにはIBinder経由で#getService()してダイレクトにアクセス可能
			mService = (( WpNaviService.WpNaviServiceLocalBinder )service ).getService();

			// サービスの WP 状態を取得，
			// ナビをリスタートした直後でなければ StopService()
			int iStatus = mService.GetStatus();
			if( m_bQuitService || iStatus != WpNaviService.STATUS_RESTART ){
				if( iStatus == WpNaviService.STATUS_NORMAL ) SetCurWayPoint( mService.iCurWayPoint );
				mService.StopNavi();
				if( bDebug ) Log.d( "WpNavi", "Service stopped:" + m_bQuitService + ":" + iStatus );
			}
			m_bQuitService = false;
			if( bDebug ) Log.d( "WpNavi", "Service's stat=" + iStatus + " WP=" + iCurWayPoint );
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
		intent.putIntegerArrayListExtra( "WayPoint", WayPoint.Points );
		mService.iCurWayPoint	= iCurWayPoint;
		mService.iNextDistance	= Pref.getInt( "key_NextDistance", 50 );
		mService.iWaitTime		= Pref.getInt( "key_WaitTime", 60 ) * 100;
		mService.bKillByRoot	= Pref.getBoolean( "key_kill_by_root", false );

		startService( intent );
	}

	final void StopService(){
		stopService( new Intent( this, WpNaviService.class ));
	}

	final void BindService(){
		//サービスとの接続を確立する。明示的にServiceを指定
		//( 特定のサービスを指定する必要がある。他のアプリケーションから知ることができない = ローカルサービス )
		bindService( new Intent( this, WpNaviService.class ), mConnection, Context.BIND_AUTO_CREATE );
		mIsBound = true;
	}

	final void UnbindService(){
		if( mIsBound ){
			// コネクションの解除
			unbindService( mConnection );
			mIsBound = false;
		}
	}
}
