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
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.PolylineOptions;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.support.v7.app.ActionBarActivity;
import android.util.Log;
import android.util.Xml;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toast;

public class WpNaviActivity extends ActionBarActivity implements FileOpenDialogListener{

	static final boolean bDebug		= BuildConfig.DEBUG;
	static final boolean bEnableAds	= true;
	private static final String strGMEUrl = "https://mapsengine.google.com/map";

	private int	iCurWayPoint	= 0;
	private Coordinate	WayPoint	= new Coordinate();
	private SharedPreferences Pref	= null;
	private String	strKmlFile		= null;

	private ArrayList<Marker>	Markers = new ArrayList<Marker>();
	
	private LinearLayout layout_ad;	//広告表示用スペース
	private AdView adView;

	/*** Activity management ************************************************/

	public void onCreate( Bundle savedInstanceState ){
		if( bDebug ) Log.d( "WpNavi", "WpNavi::onCreate" );

		super.onCreate( savedInstanceState );
		setContentView( R.layout.main );
		setUpMapIfNeeded();

		// プリファレンス
		Pref = PreferenceManager.getDefaultSharedPreferences( this );
		
		// 広告
		if( bEnableAds ){
			adView = new AdView( this );
			adView.setAdUnitId( "ca-app-pub-2092805559453853/9075326132" );
			adView.setAdSize( AdSize.SMART_BANNER );
			
			layout_ad = ( LinearLayout )findViewById( R.id.layout_ad );
			layout_ad.addView( adView );
			
			AdRequest adRequest = new AdRequest.Builder().build();
			adView.loadAd( adRequest );
		}
	}

	@Override
	protected void onResume(){
		if( bDebug ) Log.d( "WpNavi", "WpNavi::onResume" );
		super.onResume();
		if( bEnableAds ) adView.resume();	// 広告
		BindService();

		if( mMap != null ){
			// Map 移動
			CameraPosition cameraPos = new CameraPosition.Builder()
				.target( new LatLng( Pref.getFloat( "key_gmap_lat", 36.4f ), Pref.getFloat( "key_gmap_lng", 137.5f )))
				.zoom( Pref.getFloat( "key_gmap_zoom", 5 ))
				.bearing( 0 )
				.build();
			mMap.moveCamera( CameraUpdateFactory.newCameraPosition( cameraPos ));

			// マーカークリックリスナー登録
			mMap.setOnMarkerClickListener( new OnMarkerClickListener(){
				@Override
				public boolean onMarkerClick( Marker marker ){
					SetCurWayPoint( Integer.parseInt( marker.getTitle().toString().substring( 2 )) - 1 );

					return false;
				}
			});

			// KML ロード
			strKmlFile = Pref.getString( "key_kml_file", null );
			if( strKmlFile != null && WayPoint.Size() == 0 ) LoadKML( strKmlFile );
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
		ed.putString( "key_kml_file", strKmlFile );
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
		super.onDestroy();
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
				ui.setMyLocationButtonEnabled( true );
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
				.bearing( camOld.bearing )
				.tilt( camOld.tilt )
				.build();
			mMap.animateCamera( CameraUpdateFactory.newCameraPosition( camNew ));
		}
	}

	/*** Load KML ***********************************************************/

	private static final int	KML_NONE		= 0;
	private static final int	KML_POINT		= 1 << 0;
	private static final int	KML_LINESTRING	= 1 << 1;
	private static final int	KML_COORDINATES	= 1 << 2;

	@SuppressLint( "NewApi" )
	public boolean LoadKML( String strKmlFile ){
		int		iState;
		String	strTitle = null;

		if( mMap == null ) return false;

		// KMK を開く
		FileInputStream fsIn;
		try{
			fsIn = new FileInputStream( strKmlFile );
		}catch( FileNotFoundException e ){
			Toast.makeText( this, R.string.text_FileNotFound, Toast.LENGTH_LONG ).show();
			return false;
		}

		XmlPullParser xpp = Xml.newPullParser();

		WayPoint.Clear();	// WP 等のクリア
		iState = KML_NONE;

		double[] Point = new double[ 2 ];
		PolylineOptions PolyLineOpt = new PolylineOptions();
		int iMinDistance = GetPrefInt( "key_next_distance", 50 );

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
		
		// タイトル設定
		if( strTitle != null ){
			setTitle( strTitle );
		}else{
			setTitle( R.string.app_name );
		}
		
		// WP を Map に追加
		for( int i = 0; i < WayPoint.Size(); ++i ){
			MarkerOptions MakerOpt = new MarkerOptions();
			MakerOpt.position( WayPoint.GetPoint( i ));
			MakerOpt.title( String.format( "WP%d", i + 1 ));
			MakerOpt.icon( BitmapDescriptorFactory.defaultMarker(
				i != 0 ? BitmapDescriptorFactory.HUE_BLUE : BitmapDescriptorFactory.HUE_RED
			));
			//MakerOpt.snippet( location.toString());
			Markers.add( mMap.addMarker( MakerOpt ));
		}

		// Line を Map に追加
		PolyLineOpt.color( 0xFF1166FF );
		PolyLineOpt.width( 6 );
		mMap.addPolyline( PolyLineOpt );

		SetMoveCurWayPoint( 0 );
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
				FileOpenDialog fod = new FileOpenDialog( this, this, FileOpenDialog.MODE_FILE,
					new FileFilter(){
						public boolean accept( File pathname ){
							// ディレクトリだけ許可
							return !pathname.getName().startsWith( "." ) && (
								pathname.isDirectory() ||
								pathname.getName().endsWith( ".kml" ) ||
								pathname.getName().endsWith( ".xml" )
							);
						}
					}
				);
				fod.openDirectory( strKmlFile != null ? strKmlFile : Environment.getExternalStorageDirectory().getPath());
				return true;

			/*
			case R.id.itemOpenGME:
				startActivity( new Intent( Intent.ACTION_VIEW,
					Uri.parse( strGMEUrl + "/?authuser=0&action=open" )));
				return true;
			*/
				
			case R.id.itemSetting:
				Intent intent = new Intent( WpNaviActivity.this, WpNaviPreference.class );
				startActivityForResult( intent, 0 );
				return true;
		}
		return false;
	}

	public void onFileSelected( File file ){
		if( LoadKML( file.getAbsolutePath())){
			strKmlFile = file.getAbsolutePath();
		}
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
			if( iStatus != WpNaviService.STATUS_RESTART ){
				if( iStatus == WpNaviService.STATUS_NORMAL ) SetCurWayPoint( mService.iCurWayPoint );
				mService.StopNavi();
			}
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
		mService.iNextDistance	= GetPrefInt( "key_next_distance", 50 );
		mService.iWaitTime		= GetPrefInt( "key_wait_time", 6000 );
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

	/*** その他 *************************************************************/

	int GetPrefInt( String key, int iDefault ){
		int	iRet;

		try{
			iRet = Integer.parseInt( Pref.getString( key, "x" ));
		}catch( Exception e ){
			iRet = iDefault;
		}
		return iRet;
	}
}
