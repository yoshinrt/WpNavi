package jp.dds.WpNavi;

import java.util.ArrayList;

import com.google.android.gms.maps.model.LatLng;

public class Coordinate {
	ArrayList<Integer>	Points	= new ArrayList<Integer>();
	static final double ToInt = 1E7;

	final void Add( double Lng, double Lat ){
		Points.add(( int )( Lng * ToInt ));
		Points.add(( int )( Lat * ToInt ));
	}

	final LatLng GetCoordinate( int idx ){
		return new LatLng(
			Points.get( idx * 2 + 1 ) / ToInt,	// lat
			Points.get( idx * 2     ) / ToInt	// lng
		);
	}

	final int Length(){
		return Points.size() / 2;
	}

	final void Clear(){
		Points.clear();
	}
}
