package jp.dds.WpNavi;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.UiSettings;

import jp.dds.WpNavi.R.id;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.support.v4.app.FragmentActivity;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;

public class WpNaviActivity extends FragmentActivity
	implements OnClickListener {


	private static final int SHOW_CONFIG		= 0;
	static final boolean bDebug = false;

	private Button	ButtonPrevWP;
	private Button	ButtonNextWP;
	private Button	ButtonStartNavi;

	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		setContentView( R.layout.main );
		setUpMapIfNeeded();

		ButtonPrevWP = ( Button )findViewById( id.button_prevwp );
		ButtonPrevWP.setOnClickListener( this );	// download ボタンリスナ登録

		ButtonStartNavi = ( Button )findViewById( id.button_start_navi_flash );
		ButtonStartNavi.setOnClickListener( this );	// ボタンリスナ登録
	}

	public void onClick( View v ){
		Log.d( "WpNavi", "Button" );

		if( v == ButtonStartNavi ){
			Intent i = new Intent();
			i.setAction( Intent.ACTION_VIEW );
			i.setClassName( "com.google.android.apps.maps", "com.google.android.maps.driveabout.app.NavigationActivity" );
			Uri uri = Uri.parse( "google.navigation:///?ll=35.0,135.0&q=表示名" );
			i.setData(uri);
			startActivity(i);
		}else if( v == ButtonPrevWP ){

		}
	}

	@Override
	protected void onActivityResult( int requestCode, int resultCode, Intent data ){
		if( requestCode == SHOW_CONFIG ){
			finish();
		}
	}

	@Override
	protected void onDestroy(){
		super.onDestroy();
	}

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
}
