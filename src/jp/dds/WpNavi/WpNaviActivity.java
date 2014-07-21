package jp.dds.WpNavi;

import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;

import org.xmlpull.v1.XmlPullParser;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.UiSettings;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.PolylineOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
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

	/*** Activity management ************************************************/

	public void onCreate( Bundle savedInstanceState ){
		super.onCreate( savedInstanceState );

		setContentView( R.layout.main );
		setUpMapIfNeeded();
	}

	@Override
	protected void onResume() {
		super.onResume();

		// 画面が表示されるということはオートパイロットは停止
		StopService();
	}

	public void onClickStartNavi( View v ){
		// サービス開始
		StartService();
		BindService();

		// GMap kill
		KillGMaps();

		// ナビ起動
		Intent i = new Intent();
		i.setAction( Intent.ACTION_VIEW );
		i.setFlags( Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK );
		i.setClassName( "com.google.android.apps.maps", "com.google.android.maps.driveabout.app.NavigationActivity" );
		Uri uri = Uri.parse( "google.navigation:///?ll=35.0,135.0&q=表示名" );
		i.setData(uri);
		startActivity(i);

		UnbindService();
	}

	public void onClickPrevWp( View v ){
		LoadKML();
	}

	public void onClickNextWp( View v ){
		KillGMaps();
		mMap.clear();
	}

	@Override
	protected void onActivityResult( int requestCode, int resultCode, Intent data ){
		//finish();
	}

	@Override
	protected void onDestroy(){
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

	/*** Load KML ***********************************************************/

	static final int	KML_NONE		= 0;
	static final int	KML_POINT		= 1 << 0;
	static final int	KML_LINESTRING	= 1 << 1;
	static final int	KML_COORDINATES	= 1 << 2;

	static final double ToInt = 1E7;

	ArrayList<Integer>	WayPoints	= new ArrayList<Integer>();

	public boolean LoadKML(){
		int	iState;
		ArrayList<Integer>	Route	= new ArrayList<Integer>();

		if( mMap == null ) return false;

		// KMK を開く
		FileInputStream fsIn;
		try {
			fsIn = new FileInputStream( "/sdcard/test.kml" );
		}catch( FileNotFoundException e ){
			Toast.makeText( this, getResources().getText( R.string.text_FileNotFound ), Toast.LENGTH_LONG ).show();
			return false;
		}

		XmlPullParser xpp = Xml.newPullParser();

		WayPoints.clear();	// WP 等のクリア
		Route.clear();
		iState = KML_NONE;

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
							ParseCoordinate( str, WayPoints );
						}else if(( iState & KML_LINESTRING ) != 0 ){
							// ルート
							int c1 = 0, c2;

							do{
								// 空白のサーチ
								for( c2 = c1; c2 < str.length(); ++c2 ){
									if( str.charAt( c2 ) <= ' ' ) break;
								}

								if( c1 != c2 ) ParseCoordinate( str.substring( c1, c2 ), Route );

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
		if( Length( WayPoints ) == 0 ){
			Toast.makeText( this, getResources().getText( R.string.text_InvalidKMLFormat ), Toast.LENGTH_LONG ).show();
			return false;
		}

		mMap.clear();

		// WP を Map に追加
		for( int i = 0; i < Length( WayPoints ); ++i ){
			MarkerOptions options = new MarkerOptions();

			options.position( GetPoint( WayPoints, i ));
			options.title( String.format( "WP%d", i + 1 ));
			//options.snippet(location.toString());
			mMap.addMarker( options );
		}

		// Line を Map に追加
		PolylineOptions options = new PolylineOptions();
		for( int i = 0; i < Length( Route ) - 1; ++i ){
			options.add( GetPoint( Route, i ));
		}
		options.color( 0xFF0000FF );
		options.width( 6 );
		mMap.addPolyline( options );

		return true;
	}

	void ParseCoordinate( String str, ArrayList<Integer> Points ){
		int c1, c2;
		if(
			( c1 = str.indexOf( ',' )) >= 0 &&
			( c2 = str.indexOf( ',', c1 + 1 )) >= 0
		){
			AddPoint(
				Points,
				Double.parseDouble( str.substring( 0, c1 )),
				Double.parseDouble( str.substring( c1 + 1, c2 ))
			);
		}
	}

	final void AddPoint( ArrayList<Integer> Points, double Lng, double Lat ){
		Points.add(( int )( Lng * ToInt ));
		Points.add(( int )( Lat * ToInt ));
	}

	final LatLng GetPoint( ArrayList<Integer> Points, int idx ){
		return new LatLng(
			Points.get( idx * 2 + 1 ) / ToInt,	// lat
			Points.get( idx * 2     ) / ToInt	// lng
		);
	}

	final int Length( ArrayList<Integer> Points ){
		return Points.size() / 2;
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
		}
		return false;
	}

	/*** Service ************************************************************/

	//取得したServiceの保存
	private WpNaviService mBoundService;
	private boolean mIsBound;

	private ServiceConnection mConnection = new ServiceConnection(){
		@Override
		public void onServiceConnected( ComponentName className, IBinder service ){

			// サービスとの接続確立時に呼び出される
			if( bDebug ) Log.d( "WpNavi", "WpNavi::onServiceConnected" );

			// サービスにはIBinder経由で#getService()してダイレクトにアクセス可能
			mBoundService = (( WpNaviService.WpNaviServiceLocalBinder )service ).getService();
		}

		@Override
		public void onServiceDisconnected( ComponentName className ){
			if( bDebug ) Log.d( "WpNavi", "WpNavi::onServiceDisconnected" );
			// サービスとの切断( 異常系処理 )
			// プロセスのクラッシュなど意図しないサービスの切断が発生した場合に呼ばれる。
			mBoundService = null;
		}
	};

	final void StartService(){
		startService( new Intent( this, WpNaviService.class ));
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
