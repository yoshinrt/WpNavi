package jp.dds.WpNavi;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.UiSettings;

import jp.dds.WpNavi.R.id;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.support.v4.app.FragmentActivity;
import android.util.Log;
import android.util.Xml;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.Toast;

public class WpNaviActivity extends FragmentActivity {

	static final boolean bDebug = false;

	ArrayList	WayPoints	= new ArrayList<Coordinate>();
	ArrayList	Route		= new ArrayList<Coordinate>();

	/*** Activity management ************************************************/

	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		setContentView( R.layout.main );
		setUpMapIfNeeded();
	}

	public void onClickStartNavi( View v ){
		Intent i = new Intent();
		i.setAction( Intent.ACTION_VIEW );
		i.setClassName( "com.google.android.apps.maps", "com.google.android.maps.driveabout.app.NavigationActivity" );
		Uri uri = Uri.parse( "google.navigation:///?ll=35.0,135.0&q=表示名" );
		i.setData(uri);
		startActivity(i);
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

	@Override
	protected void onResume() {
		super.onResume();
		setUpMapIfNeeded();

		if( mMap != null ){
			// Keep the UI Settings state in sync with the checkboxes.
			mUiSettings.setZoomControlsEnabled( true );
			mUiSettings.setCompassEnabled( true );
			mUiSettings.setMyLocationButtonEnabled( true );
			mMap.setMyLocationEnabled( true );
			mUiSettings.setScrollGesturesEnabled( true );
			mUiSettings.setZoomGesturesEnabled( true );
			mUiSettings.setTiltGesturesEnabled( true );
			mUiSettings.setRotateGesturesEnabled( true );
		}
	}

	private void setUpMapIfNeeded(){
		// Do a null check to confirm that we have not already instantiated the map.
		if( mMap == null ){
			// Try to obtain the map from the SupportMapFragment.
			mMap = (( SupportMapFragment )getSupportFragmentManager().findFragmentById( R.id.map )).getMap();
			// Check if we were successful in obtaining the map.
			if( mMap != null ){
				setUpMap();
			}
		}
	}

	private void setUpMap() {
		mMap.setMyLocationEnabled( true );
		mUiSettings = mMap.getUiSettings();
	}

	/*** Load KML ***********************************************************/
	static final int	KML_NONE		= 0;
	static final int	KML_POINT		= 1 << 0;
	static final int	KML_LINESTRING	= 1 << 1;
	static final int	KML_COORDINATES	= 1 << 2;

	public boolean LoadKML(){
		int	iState;

		// KMK を開く
		FileInputStream fsIn;
		try {
			fsIn = new FileInputStream( "/sdcard/test.kml" );
		}catch( FileNotFoundException e ){
			Toast.makeText( this, getResources().getText( R.string.text_FileNotFound  ), Toast.LENGTH_LONG  ).show();
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

					Log.d( "WpNavi", "Tag:" + str );
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

						Log.d( "WpNavi", "Val:" + str );
						// 空白で取得したものは全て処理対象外とする
					}
					break;

				case XmlPullParser.END_TAG: // 終了タグ
					str = xpp.getName();
					Log.d( "WpNavi", "Tag/:" + str );
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
			Toast.makeText( this, getResources().getText( R.string.text_InvalidKMLFormat  ), Toast.LENGTH_LONG  ).show();
			e.printStackTrace();
			try{ fsIn.close(); }catch( IOException e2 ){}
			return false;
		}

		// close
		try{ fsIn.close(); }catch( IOException e ){}

		return true;
	}

	void ParseCoordinate( String str, ArrayList<Coordinate> ary ){
		int c1, c2;
		if(
			( c1 = str.indexOf( ',' )) >= 0 &&
			( c2 = str.indexOf( ',', c1 + 1 )) >= 0
		){
			ary.add(
				new Coordinate(
					Double.parseDouble( str.substring( 0, c1 )),
					Double.parseDouble( str.substring( c1 + 1, c2 ))
				)
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
}
