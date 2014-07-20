package jp.dds.WpNavi;

import com.google.android.gms.maps.model.LatLng;

public class Coordinate {
	int iLat;
	int	iLng;

	static final double ToInt = 1E7;

	Coordinate(){
		iLat = iLng = 0;
	}

	Coordinate( double Lng, double Lat ){
		Set( Lng, Lat );
	}

	void Set( double Lng, double Lat ){
		iLat = ( int )( Lat * ToInt );
		iLng = ( int )( Lng * ToInt );
	}

	LatLng GetCoordinate(){
		return new LatLng( iLat / ToInt, iLng / ToInt );
	}
}
