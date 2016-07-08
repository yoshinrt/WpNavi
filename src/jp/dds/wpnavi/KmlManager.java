package jp.dds.wpnavi;

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

import android.util.Log;
import android.util.Xml;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.PolylineOptions;

public class KmlManager{
	static final boolean bDebug	= BuildConfig.DEBUG;
	
	public int	Points[];
	private static final double ToInt = 1E7;

	KmlManager(){}
	KmlManager( int ary[] ){
		Points = ary;
	}

	final LatLng GetPoint( int idx ){
		return new LatLng( GetLat( idx ), GetLng( idx ));
	}

	final double GetLng( int idx ){
		return Points[ idx * 2 ] / ToInt;	// lng
	}

	final double GetLat( int idx ){
		return Points[ idx * 2 + 1 ] / ToInt;	// lat
	}

	final int Size(){
		return Points.length / 2;
	}

	private static final double _a	= 6378137.000;
	private static final double _b	= 6356752.314245;
	private static final double _e2	= ( _a * _a - _b * _b ) / ( _a * _a );
	private static final double ToRAD = Math.PI / 180;

	static final double DistancePow2(
		double dLong0, double dLati0,
		double dLong1, double dLati1
	){
		// ヒュベニの公式 http://yamadarake.jp/trdi/report000001.html
		double dx	= ( dLong1 - dLong0 ) * ToRAD;
		double dy	= ( dLati1 - dLati0 ) * ToRAD;
		double uy	= ( dLati0 + dLati1 ) / 2 * ToRAD;
		double W	= Math.sqrt( 1 - _e2 * Math.sin( uy ) * Math.sin( uy ));
		double M	= _a * ( 1 - _e2 ) / Math.pow( W, 3 );
		double N	= _a / W;

		return	dy * dy * M * M + Math.pow( dx * N * Math.cos( uy ), 2 );
	}
	
	static final double Distance(
		double dLong0, double dLati0,
		double dLong1, double dLati1
	){
		return Math.sqrt( DistancePow2( dLong0, dLati0, dLong1, dLati1 ));
	}
	
	final double DistancePow2( int iIdx, double dLong0, double dLati0 ){
		return DistancePow2( GetLng( iIdx ), GetLat( iIdx ), dLong0, dLati0 );
	}
	
	final double Distance( int iIdx, double dLong0, double dLati0 ){
		return Distance( GetLng( iIdx ), GetLat( iIdx ), dLong0, dLati0 );
	}
	
	final boolean InDistance(
		int iDistance,
		double dLong0, double dLati0,
		double dLong1, double dLati1
	){
		int iLatDist = ( int )( Math.abs( dLati0 - dLati1 ) * 110949.75926813729 );
		if( iLatDist > iDistance ) return false;
		
		int iLngDist = ( int )( Math.abs( dLong0 - dLong1 ) * Math.cos( dLati0 * ( Math.PI / 180 )) * 111448.44724952266 );
		if( iLngDist > iDistance ) return false;
		
		return iDistance * iDistance >= iLatDist * iLatDist + iLngDist * iLngDist;
	}
	
	final boolean InDistance(
		int iDistance, int iIdx,
		double dLong0, double dLati0
	){
		return InDistance( iDistance, GetLng( iIdx ), GetLat( iIdx ), dLong0, dLati0 );
	}
	
	final void Swap( int i, int j ){
		if( i == j ) return;
		
		Integer iLng = Points[ i * 2 ];
		Integer iLat = Points[ i * 2 + 1 ];
		
		Points[ i * 2     ]	= Points[ j * 2 ];
		Points[ i * 2 + 1 ]	= Points[ j * 2 + 1 ];
		Points[ j * 2     ]	= iLng;
		Points[ j * 2 + 1 ]	= iLat;
	}
	
	/*** Load KML ***********************************************************/

	private static final int	KML_NONE		= 0;
	private static final int	KML_POINT		= 1 << 0;
	private static final int	KML_LINESTRING	= 1 << 1;
	private static final int	KML_COORDINATES	= 1 << 2;
	
	class KmlInfo {
		public String m_strTitle = null;
		public double m_dMinLng =  1000, m_dMinLat =  1000;
		public double m_dMaxLng = -1000, m_dMaxLat = -1000;
		public PolylineOptions m_Polyline = new PolylineOptions();
		public int m_iErrorCode	= 0;
	};
	
	public KmlInfo LoadKML( String strKmlFile, int iMinDistance ){
		int		iState;
		KmlInfo Info = new KmlInfo();
		
		if( strKmlFile == null ){
			Info.m_iErrorCode = R.string.text_FileNotFound;
			return Info;
		}

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
				Info.m_iErrorCode = R.string.text_FileNotFound;
				return Info;
			}
		}catch( IOException e ){
			// KMZ で失敗したので，KML を開く
			if( zfIn != null ) try{ zfIn.close(); }catch( IOException e2 ){}
			
			try{
				fsIn = new FileInputStream( strKmlFile );
			}catch( FileNotFoundException e1 ){
				Info.m_iErrorCode = R.string.text_FileNotFound;
				return Info;
			}
		}

		XmlPullParser xpp = Xml.newPullParser();

		iState = KML_NONE;

		double[] Point = new double[ 2 ];
		ArrayList<Integer> TmpPoints	= new ArrayList<Integer>();
		
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
					}else if( Info.m_strTitle == null && str.equals( "name" )){
						Info.m_strTitle = xpp.nextText();
					}

					//Log.d( "WpNavi", "Tag:" + str );
					break;

				case XmlPullParser.TEXT: // タグの内容
					if(( iState & KML_COORDINATES ) != 0 ){
						str = xpp.getText();

						if(( iState & KML_POINT ) != 0 ){
							// 経由地
							ParseCoordinate( str, Info, Point );
							if(
								TmpPoints.size() == 0 ||
								!InDistance(
									iMinDistance,
									TmpPoints.get( TmpPoints.size() - 2 ) / ToInt,
									TmpPoints.get( TmpPoints.size() - 1 ) / ToInt,
									Point[ 0 ], Point[ 1 ]
								)
							){
								TmpPoints.add(( int )( Point[ 0 ] * ToInt ));
								TmpPoints.add(( int )( Point[ 1 ] * ToInt ));
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
									ParseCoordinate( str.substring( c1, c2 ), Info, Point );
									Info.m_Polyline.add( new LatLng( Point[ 1 ], Point[ 0 ] ));
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
			Info.m_iErrorCode = R.string.text_InvalidKMLFormat;
			//e.printStackTrace();
			try{ fsIn.close(); }catch( IOException e2 ){}
			return Info;
		}

		// close
		try{ fsIn.close(); }catch( IOException e ){}

		// 一応数チェック
		if(
			TmpPoints.size() == 0 ||
			Info.m_Polyline.getPoints().size() == 0
		){
			Info.m_iErrorCode = R.string.text_InvalidKMLFormat;
			return Info;
		}

		// ここまで来たらロード成功
		Points = new int[ TmpPoints.size()];
		for( int i = 0; i < TmpPoints.size(); ++i ){
			Points[ i ] = TmpPoints.get( i );
		}
		
		// WP を PolyLine にそってソートする
		SortWp( Info.m_Polyline.getPoints());
		
		return Info;
	}
	
	private final void ParseCoordinate( String str, KmlInfo Info, double Point[] ){
		int c1, c2;
		if(
			( c1 = str.indexOf( ',' )) >= 0 &&
			( c2 = str.indexOf( ',', c1 + 1 )) >= 0
		){
			Point[ 0 ] = Double.parseDouble( str.substring( 0, c1 ));
			Point[ 1 ] = Double.parseDouble( str.substring( c1 + 1, c2 ));
			
			if( Info.m_dMinLng > Point[ 0 ] ) Info.m_dMinLng = Point[ 0 ];
			if( Info.m_dMaxLng < Point[ 0 ] ) Info.m_dMaxLng = Point[ 0 ];
			if( Info.m_dMinLat > Point[ 1 ] ) Info.m_dMinLat = Point[ 1 ];
			if( Info.m_dMaxLat < Point[ 1 ] ) Info.m_dMaxLat = Point[ 1 ];
		}
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
		double dLng2Meter = KmlManager.Distance(
			dLng0, dLat0, dLng0 + 1.0 / 3600, dLat0
		) * 3600;
		
		double dLat2Meter = KmlManager.Distance(
			dLng0, dLat0, dLng0, dLat0 + 1.0 / 3600
		) * 3600;
		
		// WP を x,y 変換
		int iWpX[] = new int[ Size()];
		int iWpY[] = new int[ Size()];
		
		for( int i = 0; i < Size(); ++i ){
			iWpX[ i ] = ( int )(( GetLng( i ) - dLng0 ) * dLng2Meter );
			iWpY[ i ] = ( int )(( GetLat( i ) - dLat0 ) * dLat2Meter );
		}
		
		int x0, y0;
		int x1 = 0, y1 = 0;
		int iSortedIdx = 0;
		
		for( int iIdxLine = 0; iIdxLine < Line.size() - 1 && iSortedIdx < Size() - 1; ++iIdxLine ){
			
			x0 = x1; y0 = y1;
			x1 = ( int )(( Line.get( iIdxLine + 1 ).longitude - dLng0 ) * dLng2Meter );
			y1 = ( int )(( Line.get( iIdxLine + 1 ).latitude  - dLat0 ) * dLat2Meter );
			
			int x01 = x0 - x1;
			int y01 = y0 - y1;
			
			for( int iIdxWp = iSortedIdx; iIdxWp < Size(); ++iIdxWp ){
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
		Swap( i, j );
		int x = iWpX[ i ]; iWpX[ i ] = iWpX[ j ]; iWpX[ j ] = x;
		int y = iWpY[ i ]; iWpY[ i ] = iWpY[ j ]; iWpY[ j ] = y;
	}
}
