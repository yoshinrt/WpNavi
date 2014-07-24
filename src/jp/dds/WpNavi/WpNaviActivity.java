package jp.dds.WpNavi;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;

import org.xmlpull.v1.XmlPullParser;

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
import android.support.v4.app.FragmentActivity;
import android.util.Log;
import android.util.Xml;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

public class WpNaviActivity extends FragmentActivity {

	static final boolean bDebug = true;

	int	iCurWayPoint		= 0;
	Coordinate	WayPoint	= new Coordinate();
	SharedPreferences Pref	= null;

	ArrayList<Marker>	Markers = new ArrayList<Marker>();

	/*** Activity management ************************************************/

	public void onCreate( Bundle savedInstanceState ){
		if( bDebug ) Log.d( "WpNavi", "WpNavi::onCreate" );

		super.onCreate( savedInstanceState );
		setContentView( R.layout.main );
		setUpMapIfNeeded();

		// プリファレンス
		Pref = PreferenceManager.getDefaultSharedPreferences( this );
	}

	@Override
	protected void onResume(){
		if( bDebug ) Log.d( "WpNavi", "WpNavi::onResume" );
		super.onResume();
		BindService();

		if( mMap != null ){
			CameraPosition cameraPos = new CameraPosition.Builder()
				.target( new LatLng( Pref.getFloat( "key_gmap_lat", 36.4f ), Pref.getFloat( "key_gmap_lng", 137.5f )))
				.zoom( Pref.getFloat( "key_gmap_zoom", 5 ))
				.bearing(0)
				.build();
			mMap.moveCamera( CameraUpdateFactory.newCameraPosition( cameraPos ));

			mMap.setOnMarkerClickListener( new OnMarkerClickListener(){
				@Override
				public boolean onMarkerClick( Marker marker ){
					SetCurWayPoint( Integer.parseInt( marker.getTitle().toString().substring( 2 )) - 1 );

					return false;
				}
			});

		}
	}

	@Override
	protected void onPause(){
		if( bDebug ) Log.d( "WpNavi", "WpNavi::onPause" );
		super.onPause();
		UnbindService();

		// GMap カメラ位置保存
		CameraPosition cam = mMap.getCameraPosition();

		Editor ed = Pref.edit();
		ed.putFloat( "key_gmap_lng", ( float )cam.target.longitude );
		ed.putFloat( "key_gmap_lat", ( float )cam.target.latitude );
		ed.putFloat( "key_gmap_zoom", cam.zoom );
		ed.commit();
	}

	public void onClickStartNavi( View v ){
		if( WayPoint.Length() == 0 ) LoadKML();

		if( WayPoint.Length() == 0 ){
			Toast.makeText( this, getResources().getText( R.string.text_KMLNotLoaded ), Toast.LENGTH_LONG ).show();
			return;
		}

		// サービス開始
		StartService();
	}

	public void onClickPrevWp( View v ){
		int iNewWp = iCurWayPoint - 1;
		if( iNewWp < 0 ) iNewWp = WayPoint.Length() - 1;
		SetMoveCurWayPoint( iNewWp );
	}

	public void onClickNextWp( View v ){
		int iNewWp = iCurWayPoint + 1;
		if( iNewWp >= WayPoint.Length()) iNewWp = 0;
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
		super.onDestroy();
	}

	/*** Google Maps ********************************************************/

	private GoogleMap mMap;
	private UiSettings mUiSettings;

	private void setUpMapIfNeeded(){
		// Do a null check to confirm that we have not already instantiated the map.
		if( mMap == null ){
			// Try to obtain the map from the SupportMapFragment.
			mMap = (( SupportMapFragment )getSupportFragmentManager().findFragmentById( R.id.map )).getMap();
			// Check if we were successful in obtaining the map.
			if( mMap != null ){
				mMap.setMyLocationEnabled( true );
				mUiSettings = mMap.getUiSettings();

				// Keep the UI Settings state in sync with the checkboxes.
				mMap.setMyLocationEnabled( true );
				mUiSettings.setZoomControlsEnabled( true );
				mUiSettings.setCompassEnabled( true );
				mUiSettings.setMyLocationButtonEnabled( true );
				mUiSettings.setScrollGesturesEnabled( true );
				mUiSettings.setZoomGesturesEnabled( true );
				mUiSettings.setTiltGesturesEnabled( true );
				mUiSettings.setRotateGesturesEnabled( true );
			}
		}
	}

	final void SetCurWayPoint( int iNewWp ){
		if( mMap != null && Markers.size() != 0 ){
			// 元 CurWP のアイコンを blue にする
			Markers.get( iCurWayPoint ).setIcon(
				BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE )
			);

			Markers.get( iNewWp ).setIcon(
				BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED )
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

	static final int	KML_NONE		= 0;
	static final int	KML_POINT		= 1 << 0;
	static final int	KML_LINESTRING	= 1 << 1;
	static final int	KML_COORDINATES	= 1 << 2;

	public boolean LoadKML(){
		int	iState;

		if( mMap == null ) return false;

		// KMK を開く
		FileInputStream fsIn;
		try {
			fsIn = new FileInputStream( Environment.getExternalStorageDirectory().getPath() + "/test.kml" );
		}catch( FileNotFoundException e ){
			Toast.makeText( this, getResources().getText( R.string.text_FileNotFound ), Toast.LENGTH_LONG ).show();
			return false;
		}

		XmlPullParser xpp = Xml.newPullParser();

		WayPoint.Clear();	// WP 等のクリア
		iState = KML_NONE;

		double[] Point = new double[ 2 ];
		PolylineOptions PolyLineOpt = new PolylineOptions();

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
					}

					//Log.d( "WpNavi", "Tag:" + str );
					break;

				case XmlPullParser.TEXT: // タグの内容
					if(( iState & KML_COORDINATES ) != 0){
						str = xpp.getText();

						if(( iState & KML_POINT ) != 0 ){
							// 経由地
							ParseCoordinate( str, Point );
							WayPoint.Add( Point[ 0 ], Point[ 1 ] );
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
			Toast.makeText( this, getResources().getText( R.string.text_InvalidKMLFormat ), Toast.LENGTH_LONG ).show();
			// e.printStackTrace();
			try{ fsIn.close(); }catch( IOException e2 ){}
			return false;
		}

		// close
		try{ fsIn.close(); }catch( IOException e ){}

		// 一応数チェック
		if( WayPoint.Length() == 0 ){
			Toast.makeText( this, getResources().getText( R.string.text_InvalidKMLFormat ), Toast.LENGTH_LONG ).show();
			return false;
		}

		// ここまで来たらロード成功

		mMap.clear();
		Markers.clear();
		iCurWayPoint = 0;

		// WP を Map に追加
		for( int i = 0; i < WayPoint.Length(); ++i ){
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
		inflater.inflate( R.menu.optionsmenu, menu );
		return true;
	}

	@Override
	public boolean onOptionsItemSelected( MenuItem item ){
		switch( item.getItemId()){
			case R.id.itemLoadKML:
				LoadKML();
				return true;

			case R.id.itemSetting:
				Intent intent = new Intent( WpNaviActivity.this, WpNaviPreference.class );
				startActivityForResult( intent, 0 );
				return true;
		}
		return false;
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
			int iStatus;
			if(( iStatus =  mService.GetStatus()) != WpNaviService.STATUS_RESTART ){
				if( iStatus == WpNaviService.STATUS_NORMAL ) SetCurWayPoint( mService.iCurWayPoint );
				if( bDebug ) Log.d( "WpNavi", "Service's WP=" + iCurWayPoint );
				mService.StopNavi();
			}
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
		mService.iWaitTime		= GetPrefInt( "key_wait_time", 3000 );

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
