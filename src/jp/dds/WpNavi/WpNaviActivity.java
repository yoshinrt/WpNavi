package jp.dds.WpNavi;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import org.xmlpull.v1.XmlPullParser;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.UiSettings;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.PolylineOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
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
	Coordinate	WayPoint	= new Coordinate();
	int	iCurWayPoint		= 0;

	/*** Activity management ************************************************/

	public void onCreate( Bundle savedInstanceState ){
		super.onCreate( savedInstanceState );

		setContentView( R.layout.main );
		setUpMapIfNeeded();
	}

	@Override
	protected void onResume(){
		super.onResume();
		
		// 画面が表示されるということはオートパイロットは停止
		StopService();
	}

	public void onClickStartNavi( View v ){
		LoadKML();
		
		if(  WayPoint.Length() == 0 ){
			Toast.makeText( this, getResources().getText( R.string.text_KMLNotLoaded ), Toast.LENGTH_LONG ).show();
			return;
		}

		// サービス開始
		StartService();
		// サービスに接続して，onServiceConnected で実際に
		// サービスの状態を get してから，サービスを止める
		//BindService();
		//UnbindService();
		
		finish();
	}

	public void onClickPrevWp( View v ){
		--iCurWayPoint;
		if( iCurWayPoint < 0 ) iCurWayPoint = WayPoint.Length() - 1;
	}

	public void onClickNextWp( View v ){
		++iCurWayPoint;
		if( iCurWayPoint >= WayPoint.Length()) iCurWayPoint = 0;
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

	public boolean LoadKML(){
		int	iState;
		Coordinate	Route	= new Coordinate();

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

		WayPoint.Clear();	// WP 等のクリア
		Route.Clear();
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
							ParseCoordinate( str, WayPoint );
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
		if( WayPoint.Length() == 0 ){
			Toast.makeText( this, getResources().getText( R.string.text_InvalidKMLFormat ), Toast.LENGTH_LONG ).show();
			return false;
		}

		// ここまで来たらロード成功

		mMap.clear();
		iCurWayPoint = 0;

		// WP を Map に追加
		for( int i = 0; i < WayPoint.Length(); ++i ){
			MarkerOptions options = new MarkerOptions();

			options.position( WayPoint.GetPoint( i ));
			options.title( String.format( "WP%d", i + 1 ));
			//options.snippet(location.toString());
			mMap.addMarker( options );
		}

		// Line を Map に追加
		PolylineOptions options = new PolylineOptions();
		for( int i = 0; i < Route.Length() - 1; ++i ){
			options.add( Route.GetPoint( i ));
		}
		options.color( 0xFF0000FF );
		options.width( 6 );
		mMap.addPolyline( options );

		return true;
	}

	void ParseCoordinate( String str, Coordinate Points ){
		int c1, c2;
		if(
			( c1 = str.indexOf( ',' )) >= 0 &&
			( c2 = str.indexOf( ',', c1 + 1 )) >= 0
		){
			Points.Add(
				Double.parseDouble( str.substring( 0, c1 )),
				Double.parseDouble( str.substring( c1 + 1, c2 ))
			);
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
		Intent intent = new Intent( this, WpNaviService.class );
		intent.putIntegerArrayListExtra( "WayPoint", WayPoint.Points );
		intent.putExtra( "CurWayPoint", iCurWayPoint );
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
