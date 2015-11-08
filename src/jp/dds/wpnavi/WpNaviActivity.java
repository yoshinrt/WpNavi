package jp.dds.wpnavi;

import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.xmlpull.v1.XmlPullParser;

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
import com.google.android.gms.maps.model.PolylineOptions;
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
import android.util.Xml;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;
import jp.dds.dds_lib.FileOpenDialog;

public class WpNaviActivity extends ActionBarActivity
	implements FileOpenDialog.FileOpenDialogListener {

	static final boolean bDebug	= BuildConfig.DEBUG;
	static boolean m_bEnableAds	= true;
	private static final String m_strGMEUrl = "https://www.google.com/maps/d";
	private static final String m_strDownloadKmlName	= "/wpnavi.kml";
	private static final String m_strDownloadKmlNameTmp	= "/wpnavi.kml.tmp";

	private int	m_iCurWayPoint			= 0;
	private Coordinate	m_WayPoint		= new Coordinate();
	private SharedPreferences m_Pref	= null;
	private String	m_strKmlFile		= null;
	private boolean m_bDownloading		= false;

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
		
		setUpMapIfNeeded();
		GMEIntent( getIntent());
		
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
	}

	@Override
	protected void onResume(){
		if( bDebug ) Log.d( "WpNavi", "WpNavi::onResume" );
		super.onResume();
		if( m_bEnableAds ) m_adView.resume();	// 広告
		BindService();
		
		if( m_Map != null ){
			// マーカークリックリスナー登録
			m_Map.setOnMarkerClickListener( new OnMarkerClickListener(){
				@Override
				public boolean onMarkerClick( Marker marker ){
					SetCurWayPoint( Integer.parseInt( marker.getTitle().toString().substring( 2 )) - 1 );

					return false;
				}
			});

			// KML ロード
			m_iCurWayPoint = m_Pref.getInt( "key_waypoint", 0 );
			m_strKmlFile = m_Pref.getString( "key_kml_file", null );
			
			// 渋滞情報
			m_Map.setTrafficEnabled( m_Pref.getBoolean( "key_traffic_info", false ));
		}
	}

	@Override 
	public void onWindowFocusChanged( boolean hasFocus ){
		super.onWindowFocusChanged( hasFocus );
		
		if( m_Map != null ){
			TypedValue tv = new TypedValue();
			if( getTheme().resolveAttribute(android.R.attr.actionBarSize, tv, true )){
			    m_Map.setPadding( 0,
			    	TypedValue.complexToDimensionPixelSize( tv.data,getResources().getDisplayMetrics()),
			    	0,
			    	findViewById( R.id.buttonPrevWp ).getHeight()
			    );
			}
			
			if( m_strKmlFile != null && m_WayPoint.Size() == 0 ) LoadKML( m_strKmlFile, m_iCurWayPoint );
		}
	}
	
	@Override
	protected void onPause(){
		if( bDebug ) Log.d( "WpNavi", "WpNavi::onPause" );
		if( m_bEnableAds ) m_adView.pause();	// 広告
		super.onPause();
		UnbindService();

		Editor ed = m_Pref.edit();
		
		// GMap カメラ位置保存
		if( m_Map != null ){
			CameraPosition cam = m_Map.getCameraPosition();
			
			ed.putFloat( "key_gmap_lng", ( float )cam.target.longitude );
			ed.putFloat( "key_gmap_lat", ( float )cam.target.latitude );
			ed.putFloat( "key_gmap_zoom", cam.zoom );
			ed.putInt( "key_waypoint", m_iCurWayPoint );
			ed.putString( "key_kml_file", m_strKmlFile );
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
		StartService();
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

	private void setUpMapIfNeeded(){
		// Do a null check to confirm that we have not already instantiated the map.
		if( m_Map == null ){
			// Try to obtain the map from the SupportMapFragment.
			m_Map = (( SupportMapFragment )getSupportFragmentManager().findFragmentById( R.id.map )).getMap();
			// Check if we were successful in obtaining the map.
			if( m_Map != null ){
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

				// Map 移動
				CameraPosition cameraPos = new CameraPosition.Builder()
					.target( new LatLng( m_Pref.getFloat( "key_gmap_lat", 0f ), m_Pref.getFloat( "key_gmap_lng", 0f )))
					.zoom( m_Pref.getFloat( "key_gmap_zoom", 0 ))
					.bearing( 0 )
					.build();
				m_Map.moveCamera( CameraUpdateFactory.newCameraPosition( cameraPos ));
			}
		}
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

			CameraPosition camOld = m_Map.getCameraPosition();
			CameraPosition camNew = new CameraPosition.Builder()
				.target( m_Markers.get( iNewWp ).getPosition())
				.zoom( camOld.zoom )
				.build();
			m_Map.animateCamera( CameraUpdateFactory.newCameraPosition( camNew ));
		}
	}

	/*** Load KML ***********************************************************/

	private static final int	KML_NONE		= 0;
	private static final int	KML_POINT		= 1 << 0;
	private static final int	KML_LINESTRING	= 1 << 1;
	private static final int	KML_COORDINATES	= 1 << 2;

	public boolean LoadKML( String strKmlFile, int iWayPoint ){
		int		iState;
		String	strTitle = null;
		
		if( m_Map == null || strKmlFile == null ) return false;

		ZipFile zfIn = null;
		InputStream fsIn = null;
		
		try {
			// KMZ を開いてみる
			zfIn = new ZipFile( strKmlFile );
			
			for( Enumeration<? extends ZipEntry> enumulation = zfIn.entries(); enumulation.hasMoreElements();){
				ZipEntry entry = enumulation.nextElement();
				// System.out.println(entry.getName());
				if( entry.isDirectory()) continue;
				
				if( bDebug ) Log.d( "WpNavi", "LoadKML:ZipEntry:" + entry.getName());
				if( entry.getName().endsWith( ".kml" )){
					fsIn = zfIn.getInputStream( entry );
					break;
				}
			}
			// kmz 中に kml がなかった
			if( fsIn == null ){
				zfIn.close();
				Toast.makeText( this, R.string.text_FileNotFound, Toast.LENGTH_LONG ).show();
				return false;
			}
		}catch( IOException e ){
			// KMZ で失敗したので，KML を開く
			if( zfIn != null ) try{ zfIn.close(); }catch( IOException e2 ){}
			
			try{
				fsIn = new FileInputStream( strKmlFile );
			}catch( FileNotFoundException e1 ){
				Toast.makeText( this, R.string.text_FileNotFound, Toast.LENGTH_LONG ).show();
				return false;
			}
		}

		XmlPullParser xpp = Xml.newPullParser();

		m_WayPoint.Clear();	// WP 等のクリア
		iState = KML_NONE;

		double[] Point = new double[ 6 ];
		Point[ 2 ] = Point[ 3 ] = 1000;		// min Lng, Lat
		Point[ 4 ] = Point[ 5 ] = -1000;	// max Lng, Lat
		
		PolylineOptions PolyLineOpt = new PolylineOptions();
		int iMinDistance = m_Pref.getInt( "key_NextDistance", 50 );

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
								m_WayPoint.Size() == 0 ||
								!m_WayPoint.InDistance( iMinDistance, m_WayPoint.Size() - 1, Point[ 0 ], Point[ 1 ] )
							){
								m_WayPoint.Add( Point[ 0 ], Point[ 1 ] );
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
		if( m_WayPoint.Size() == 0 ){
			Toast.makeText( this, R.string.text_InvalidKMLFormat, Toast.LENGTH_LONG ).show();
			return false;
		}

		// ここまで来たらロード成功

		m_Map.clear();
		m_Markers.clear();
		m_iCurWayPoint = 0;
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
		
		// WP を PolyLine にそってソートする
		SortWp( PolyLineOpt.getPoints());
		
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
		PolyLineOpt.color( 0xFF1166FF );
		PolyLineOpt.width(( int )( 6 * fDipScale ));
		m_Map.addPolyline( PolyLineOpt );

		SetCurWayPoint(
			iWayPoint >= 0 ? iWayPoint :
			m_Pref.getBoolean( "key_ReverseOrder", false ) ? m_WayPoint.Size() - 1 : 0
		);
		
		// ルートが 180W をまたいでいたら，補正
		if( Point[ 4 ] - Point[ 2 ] > 180 ){
			double tmp = Point[ 4 ];
			Point[ 4 ] = Point[ 2 ];
			Point[ 2 ] = tmp;
		}
		
		// ルート全体に移動
		m_Map.animateCamera(
			CameraUpdateFactory.newLatLngBounds(
				LatLngBounds.builder()
					.include( new LatLng( Point[ 5 ], Point[ 4 ] ))
					.include( new LatLng( Point[ 3 ], Point[ 2 ] ))
					.build(),
				( int )( 16 * fDipScale )	// padding
			)
		);
		
		return true;
	}
	
	/*** WP を PolyLine にそってソートする **********************************/
	
	private final static int iOnlineDist = 5;
	private final static int iOnlineDistPow2 = iOnlineDist * iOnlineDist;
	
	// ルート線分の端点からこれだけ離れている WP は online 判定から除外
	private final static int iDistTh = 1000; // [m]
	
	private final void SortWp( List<LatLng> Line ){
		// 原点
		double dLng0 = Line.get( 0 ).longitude;
		double dLat0 = Line.get( 0 ).latitude;
		
		// 簡易 x,y 変換用のパラメータ
		double dLng2Meter = Coordinate.Distance(
			dLng0, dLat0, dLng0 + 1.0 / 3600, dLat0
		) * 3600;
		
		double dLat2Meter = Coordinate.Distance(
			dLng0, dLat0, dLng0, dLat0 + 1.0 / 3600
		) * 3600;
		
		// WP を x,y 変換
		int iWpX[] = new int[ m_WayPoint.Size()];
		int iWpY[] = new int[ m_WayPoint.Size()];
		
		for( int i = 0; i < m_WayPoint.Size(); ++i ){
			iWpX[ i ] = ( int )(( m_WayPoint.GetLng( i ) - dLng0 ) * dLng2Meter );
			iWpY[ i ] = ( int )(( m_WayPoint.GetLat( i ) - dLat0 ) * dLat2Meter );
		}
		
		int x0, y0;
		int x1 = 0, y1 = 0;
		int iSortedIdx = 0;
		
		for( int iIdxLine = 0; iIdxLine < Line.size() - 1 && iSortedIdx < m_WayPoint.Size() - 1; ++iIdxLine ){
			
			x0 = x1; y0 = y1;
			x1 = ( int )(( Line.get( iIdxLine + 1 ).longitude - dLng0 ) * dLng2Meter );
			y1 = ( int )(( Line.get( iIdxLine + 1 ).latitude  - dLat0 ) * dLat2Meter );
			
			int x01 = x0 - x1;
			int y01 = y0 - y1;
			
			for( int iIdxWp = iSortedIdx; iIdxWp < m_WayPoint.Size(); ++iIdxWp ){
				int xp0 = iWpX[ iIdxWp ] - x0;
				int yp0 = iWpY[ iIdxWp ] - y0;
				int xp1 = iWpX[ iIdxWp ] - x1;
				int yp1 = iWpY[ iIdxWp ] - y1;
				
				// 線分端点と 1000m 離れているので online 判定スキップ
				if(
					( Math.abs( xp0 ) > iDistTh || Math.abs( yp0 ) > iDistTh ) &&
					( Math.abs( xp1 ) > iDistTh || Math.abs( yp1 ) > iDistTh )
				) continue;
				
				// L1<-L0 と Wp<-L0 がなす角が 90度以上なら，距離は L0～Wp となる
				if( -x01 * xp0 - y01 * yp0 <= 0 ){
					if( xp0 * xp0 + yp0 * yp0 <= iOnlineDistPow2 ){
						if( bDebug ) Log.d( "WpNavi", String.format(
							"WpSortP[%d]: %d<->%d, %f", iIdxLine, iSortedIdx, iIdxWp, Math.sqrt( xp0 * xp0 + yp0 * yp0 )
						));
						Swap( iSortedIdx, iIdxWp, iWpX, iWpY );
						++iSortedIdx;
						break;
					}
				}
				
				// L0<-L1 と Wp<-L1 がなす角が 90度以下なら，距離は L1<-L0 線分～Wp となる
				else{
					if(
						x01 * xp1 + y01 * yp1 >= 0 &&
						Math.abs( x01 * yp1 - y01 * xp1 ) <= iOnlineDist * ( int )Math.sqrt( x01 * x01 + y01 * y01 )
					){
						if( bDebug ) Log.d( "WpNavi", String.format(
							"WpSortL[%d]: %d<->%d, %f", iIdxLine, iSortedIdx, iIdxWp, Math.abs( x01 * yp1 - y01 * xp1 ) / Math.sqrt( x01 * x01 + y01 * y01 )
						));
						Swap( iSortedIdx, iIdxWp, iWpX, iWpY );
						++iSortedIdx;
						break;
					}
				}
			}
		}
	}
	
	private final void Swap( int i, int j, int iWpX[], int iWpY[] ){
		m_WayPoint.Swap( i, j );
		int x = iWpX[ i ]; iWpX[ i ] = iWpX[ j ]; iWpX[ j ] = x;
		int y = iWpY[ i ]; iWpY[ i ] = iWpY[ j ]; iWpY[ j ] = y;
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
		GMEIntent( intent );
	}

	final boolean GMEIntent( Intent intent ){
		if( intent == null ) return false;
		if( bDebug ) Log.d( "WpNavi", "GMEIntent:Action:" + intent.getAction());
		
		// notification から呼ばれた
		if( intent.getBooleanExtra( "quit_service", false )){
			if( bDebug ) Log.d( "WpNavi", "GMEIntent:Killed by notification" );
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
			if( iStatus == WpNaviService.STATUS_RUNNING ){
				if( iStatus == WpNaviService.STATUS_RUNNING ) SetCurWayPoint( mService.iCurWayPoint );
				mService.StopNavi();
				if( bDebug ) Log.d( "WpNavi", "Service stopped:" + iStatus );
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
		intent.putIntegerArrayListExtra( "WayPoint", m_WayPoint.Points );
		mService.iCurWayPoint	= m_iCurWayPoint;
		mService.iNextDistance	= m_Pref.getInt( "key_NextDistance", 50 );
		mService.iWaitTime		= m_Pref.getInt( "key_WaitTime", 60 ) * 100;
		mService.bKillByRoot	= m_Pref.getBoolean( "key_kill_by_root", false );
		mService.bReverseOrder	= m_Pref.getBoolean( "key_ReverseOrder", false );
		
		mService.m_MsgHandler	= new Handler(){
			public void handleMessage( Message Msg ){
				OnLocationChanged( mService.m_Location );
			}
		};
		
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
	
	/*** 無電波モード *******************************************************/
	
	// 電波なしナビモード開始・終了
	void StartNoSigNavi(){
		Button btn = ( Button )findViewById( R.id.buttonStartNavi );
		btn.setText(( String )getText( R.string.button_stop_navi ));
	}
	
	void StopNoSigNavi(){
		if( m_Map != null ){
			CameraPosition camOld = m_Map.getCameraPosition();
			CameraPosition camNew = new CameraPosition.Builder()
				.target( camOld.target )
				.zoom( camOld.zoom )
				.tilt( 0 )
				.bearing( 0 )
				.build();
			m_Map.animateCamera( CameraUpdateFactory.newCameraPosition( camNew ));
		}
		
		Button btn = ( Button )findViewById( R.id.buttonStartNavi );
		btn.setText(( String )getText( R.string.button_start_navi ));
	}
	
	// 一定時間ごとに電波チェック & 地図位置更新
	public void OnLocationChanged( Location location ){
		if( m_Map != null ){
			CameraPosition camOld = m_Map.getCameraPosition();
			CameraPosition camNew = new CameraPosition.Builder()
				.target( new LatLng( location.getLatitude(), location.getLongitude()))
				.zoom( camOld.zoom )
				.tilt( 60 )
				.bearing( location.getBearing())
				.build();
			m_Map.animateCamera( CameraUpdateFactory.newCameraPosition( camNew ));
		}
	}
}
