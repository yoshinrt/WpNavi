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
}
