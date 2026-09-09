package jp.dds.wpnavi

import android.content.res.Configuration
import android.os.Bundle
import android.preference.PreferenceActivity
import android.util.Log

class WpNaviPreference : PreferenceActivity() {
    // create
    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.preference)
    }

    // 画面回転時の destroy 防止
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (bDebug) Log.d("WpNaviUtility", "WpNaviPreference::onConfigurationChanged")
    }

    companion object {
        private val bDebug: Boolean = WpNaviActivity.Companion.bDebug
    }
}
