package jp.dds.wpnavi;

import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.res.Configuration;
import android.os.Bundle;
import android.preference.EditTextPreference;
import android.preference.PreferenceActivity;
import android.util.Log;

public class WpNaviPreference extends PreferenceActivity
	implements OnSharedPreferenceChangeListener{

	private static final boolean bDebug = WpNaviActivity.bDebug;

	private EditTextPreference	EditNextDistance;
	private EditTextPreference	EditWaitTime;

	// create
	@Override
	public void onCreate( Bundle savedInstanceState ){
		super.onCreate( savedInstanceState );

		addPreferencesFromResource( R.xml.preference );

		EditNextDistance	= ( EditTextPreference	)getPreferenceScreen().findPreference( "key_next_distance" );
		EditWaitTime		= ( EditTextPreference	)getPreferenceScreen().findPreference( "key_wait_time" );
	}

	// callback 登録・解除
	@Override
	protected void onResume(){
		super.onResume();
		SharedPreferences Pref = getPreferenceScreen().getSharedPreferences();
		SetupSummery( Pref, null );
		Pref.registerOnSharedPreferenceChangeListener( this );
	}

	@Override
	protected void onPause(){
		super.onPause();
		getPreferenceScreen().getSharedPreferences().unregisterOnSharedPreferenceChangeListener( this );
	}

	// 設定変更時
	private void SetupSummery( SharedPreferences Pref, String key ){
		if( key == null || key.equals( "key_next_distance" )){
			EditNextDistance.setSummary( Pref.getString( "key_next_distance", null ) + "m" );
		}

		if( key == null || key.equals( "key_wait_time" )){
			EditWaitTime.setSummary( Pref.getString( "key_wait_time", null ) +
				getResources().getText( R.string.text_millisecond )
			);
		}
	}

	@Override
	protected void onDestroy(){
		super.onDestroy();
		if( bDebug ) Log.d( "WpNaviUtility", "WpNaviPreference::onDestroy finished" );
	}

	// 画面回転時の destroy 防止
	@Override
	public void onConfigurationChanged( Configuration newConfig ){
		super.onConfigurationChanged( newConfig );
		if( bDebug ) Log.d( "WpNaviUtility", "WpNaviPreference::onConfigurationChanged" );
	}

	public void onSharedPreferenceChanged( SharedPreferences Pref, String key ){
		SetupSummery( Pref, key );
	}
}
