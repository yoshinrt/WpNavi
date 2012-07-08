package jp.dds.MtkUtility;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

public class MtkUtilityActivity extends Activity {

	private static final int SHOW_CONFIG		= 0;
	static final boolean bDebug = false; //true;

	@Override
	protected void onCreate( final Bundle savedInstanceState ){
		super.onCreate( savedInstanceState );

		// preference screen 起動
		Intent intent = new Intent( MtkUtilityActivity.this, MtkPreference.class );
		startActivityForResult( intent, SHOW_CONFIG );
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
		//Vsd.KillThread();
		//if( bDebug ) Log.d( "VSDroid", "Activity::onDestroy finished" );
	}
}
