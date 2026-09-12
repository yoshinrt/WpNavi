package jp.dds.wpnavi

import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceFragmentCompat

class WpNaviPreference : AppCompatActivity() {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		if (savedInstanceState == null) {
			supportFragmentManager
				.beginTransaction()
				.replace(android.R.id.content, SettingsFragment())
				.commit()
		}

		// アクションバーに戻るボタンを表示
		supportActionBar?.setDisplayHomeAsUpEnabled(true)
	}

	override fun onSupportNavigateUp(): Boolean {
		finish()
		return true
	}

	// 画面回転時の destroy 防止
	override fun onConfigurationChanged(newConfig: Configuration) {
		super.onConfigurationChanged(newConfig)
		if (bDebug) Log.d("WpNaviUtility", "WpNaviPreference::onConfigurationChanged")
	}

	/**
	 * 設定画面用 Fragment
	 */
	class SettingsFragment : PreferenceFragmentCompat() {
		override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
			setPreferencesFromResource(R.xml.preference, rootKey)
		}
	}

	companion object {
		private val bDebug: Boolean = WpNaviActivity.bDebug
	}
}
