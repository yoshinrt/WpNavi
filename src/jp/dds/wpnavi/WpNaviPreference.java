package jp.dds.wpnavi;

import android.content.res.Configuration;
import android.os.Bundle;
import android.preference.PreferenceActivity;
import android.util.Log;

public class WpNaviPreference extends PreferenceActivity{

	private static final boolean bDebug = WpNaviActivity.bDebug;

	// create
	@Override
	public void onCreate( Bundle savedInstanceState ){
		super.onCreate( savedInstanceState );
		addPreferencesFromResource( R.xml.preference );
	}

	// 画面回転時の destroy 防止
	@Override
	public void onConfigurationChanged( Configuration newConfig ){
		super.onConfigurationChanged( newConfig );
		if( bDebug ) Log.d( "WpNaviUtility", "WpNaviPreference::onConfigurationChanged" );
	}
}
