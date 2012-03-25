package jp.dds.MtkUtility;

import android.app.ProgressDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.res.Configuration;
import android.os.Bundle;
import android.preference.*;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.Toast;
import android.view.View.OnClickListener;

import java.io.File;
import java.lang.CharSequence;
import java.util.ArrayList;
import java.util.Set;
import jp.dds.MtkUtility.R.id;

public class MtkPreference extends PreferenceActivity implements OnSharedPreferenceChangeListener, OnClickListener {

	private ListPreference		ListInterval;
	private ListPreference		ListBTDevices;

	MtkDriver	Mtk	= null;
	final String MTKUTIL_ROOT = "/sdcard/mtk_util";
	final boolean bDebug = true;

	private static ProgressDialog WaitDialog;

	// create
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		
		setContentView( R.layout.main );
		addPreferencesFromResource( R.xml.preference );

		ListInterval	= ( ListPreference	 )getPreferenceScreen().findPreference( "key_interval" );
		ListBTDevices	= ( ListPreference	 )getPreferenceScreen().findPreference( "key_bt_devices" );

		/*** Mtk オープン ***/
		SharedPreferences Pref = getPreferenceScreen().getSharedPreferences();
		Mtk = new MtkDriver();
		if( Mtk.Open( Pref.getString( "key_bt_devices", "00:00:00:00:00:00" )) < 0 ){
			Toast.makeText( this, "Bluetooth connection failed.", Toast.LENGTH_LONG ).show();
		}
		
		//////////////////////////////////////////////////////////////////////
		// BT デバイスリストの作成
		// http://web.dimension-maker.info/archives/2010/11/22163814.html
		//////////////////////////////////////////////////////////////////////

		// 項目の取得。 ArrayList と Arrayの変換
		ArrayList<CharSequence> entriesList = new ArrayList<CharSequence> ();
		ArrayList<CharSequence> entryValuesList = new ArrayList<CharSequence> ();

		// Get the local Bluetooth adapter
		BluetoothAdapter mBtAdapter = BluetoothAdapter.getDefaultAdapter();

		// Get a set of currently paired devices
		Set<BluetoothDevice> pairedDevices = mBtAdapter.getBondedDevices();

		// If there are paired devices, add each one to the ArrayAdapter
		String s;
		for (BluetoothDevice device : pairedDevices) {
			s = device.getName() + " / " + device.getAddress();
			entriesList.add( s );
			entryValuesList.add( s );
		}

		// 各配列を再度当てはめる。
		CharSequence entries[]		= entriesList.toArray( new CharSequence[]{} );
		CharSequence entryValues[]	= entryValuesList.toArray( new CharSequence[]{} );

		ListBTDevices.setEntries( entries );
		ListBTDevices.setEntryValues( entryValues );

		// download ボタンリスナ登録
		Button ButtonDownload = ( Button )findViewById( id.button_download );
		ButtonDownload.setOnClickListener( this );

		// プログレスバー設定
		ProgressBar progressBar = ( ProgressBar )findViewById( id.progressBar_flash_usage );
		progressBar.setMax( 4 * 1024 * 1024 );	// 4MB
		progressBar.setProgress( Mtk.GetRecordSize());

		// interval の設定取得
		Editor ed = Pref.edit();
		ed.putString( "key_interval", Double.toString( 1000.0 / Mtk.GetInterval()));
		ed.commit();
	}

	public void onClick( View v ){
		Log.d( "MtkUtility", "Button" );
		File dir;
		dir = new File( MTKUTIL_ROOT ); dir.mkdir();

		// 進行状況ダイアログ
		WaitDialog = new ProgressDialog( this );
		WaitDialog.setMessage( "Reading log data..." );
		WaitDialog.setProgressStyle( ProgressDialog.STYLE_HORIZONTAL );
		WaitDialog.setIndeterminate( false );

		WaitDialog.setMax( Mtk.iLogSize );		// 最大値の設定
		WaitDialog.incrementProgressBy( 0 );	// セカンダリ値の設定
		WaitDialog.setCancelable( false );		// キャンセル設定

		// ProgressDialog の Cancel ボタン
		WaitDialog.setButton(
			DialogInterface.BUTTON_NEGATIVE,
			"Cancel",
			new DialogInterface.OnClickListener(){
				public void onClick( DialogInterface dialog, int which ){
					dialog.cancel();	// ProgressDialog をキャンセル
					//Mtk.Cancel();		// 実行中の処理をキャンセル
				}
			}
		);

		if(
			Mtk.GetLog( WaitDialog ) >= 0 &&
			Mtk.SaveBinLog( MTKUTIL_ROOT ) >= 0 &&
			Mtk.SaveNMEA( MTKUTIL_ROOT ) >= 0
		){
			if( bDebug ) Log.d( "MtkUtility", "MtkPreference::SaveLog finished" );
			return;
		}
		Toast.makeText( this, "NMEA save failed.", Toast.LENGTH_LONG ).show();
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

		if( key == null || key.equals( "key_interval" )){
			ListInterval.setSummary( Pref.getString( "key_interval", "1" ));
		}

		if( key == null || key.equals( "key_bt_devices" )){
			ListBTDevices.setSummary( Pref.getString( "key_bt_devices", "Not selected" ));
		}
	}

	public void onSharedPreferenceChanged( SharedPreferences Pref, String key ){
		SetupSummery( Pref, key );

		if( key.equals( "key_interval" )){
			Mtk.SetInterval(( int )( 1000.0 / Double.parseDouble(
				Pref.getString( key, "1" )
			)));
		}
	}

	@Override
	protected void onDestroy(){
		super.onDestroy();
		Mtk.Close();
		if( bDebug ) Log.d( "MtkUtility", "MtkPreference::onDestroy finished" );
	}

	@Override
	public void onConfigurationChanged(Configuration newConfig) {
		super.onConfigurationChanged(newConfig);
		if( bDebug ) Log.d( "MtkUtility", "MtkPreference::onConfigurationChanged" );
	}
}
