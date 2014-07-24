package jp.dds.WpNavi;

import java.util.ArrayList;

import com.google.android.gms.maps.model.LatLng;

public class Coordinate {
	ArrayList<Integer>	Points;
	static final double ToInt = 1E7;

	Coordinate(){
		Points	= new ArrayList<Integer>();
	}

	Coordinate( ArrayList<Integer> ary ){
		Points = ary;
	}

	final void Add( double Lng, double Lat ){
		Points.add(( int )( Lng * ToInt ));
		Points.add(( int )( Lat * ToInt ));
	}

	final LatLng GetPoint( int idx ){
		return new LatLng( GetLat( idx ), GetLng( idx ));
	}

	final double GetLng( int idx ){
		return Points.get( idx * 2     ) / ToInt;	// lng
	}

	final double GetLat( int idx ){
		return Points.get( idx * 2 + 1 ) / ToInt;	// lat
	}

	final int Length(){
		return Points.size() / 2;
	}

	final void Clear(){
		Points.clear();
	}

	static final double GetLength(
		double dLong0, double dLati0,
		double dLong1, double dLati1
	){
		// ヒュベニの公式 http://yamadarake.jp/trdi/report000001.html
		final double a	= 6378137.000;
		final double b	= 6356752.314245;
		final double e2	= ( a * a - b * b ) / ( a * a );
		final double ToRAD = 180 / Math.PI;

		double dx	= ( dLong1 - dLong0 ) * ToRAD;
		double dy	= ( dLati1 - dLati0 ) * ToRAD;
		double uy	= ( dLati0 + dLati1 ) / 2 * ToRAD;
		double W	= Math.sqrt( 1 - e2 * Math.sin( uy ) * Math.sin( uy ));
		double M	= a * ( 1 - e2 ) / Math.pow( W, 3 );
		double N	= a / W;

		return	Math.sqrt( dy * dy * M * M + Math.pow( dx * N * Math.cos( uy ), 2 ));
	}

	final double GetLength( int iIdx, double dLong0, double dLati0 ){
		return GetLength( GetLng( iIdx ), GetLat( iIdx ), dLong0, dLati0 );
	}
}
