package com.mediasave

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial

class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        findViewById<MaterialToolbar>(R.id.aboutToolbar)
            .setNavigationOnClickListener { finish() }

        // ── Settings: third-party Douyin toggle ──────────────────────────────────
        val tpSwitch = findViewById<SwitchMaterial>(R.id.thirdPartyDouyinSwitch)
        tpSwitch.isChecked = AppSettings.thirdPartyDouyin
        tpSwitch.setOnCheckedChangeListener { _, checked ->
            AppSettings.setThirdPartyDouyin(this, checked)
        }

        // ── About info ───────────────────────────────────────────────────────────
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) {
            null
        } ?: "?"
        findViewById<TextView>(R.id.aboutVersion).text =
            getString(R.string.about_version_label) + " " + versionName

        findViewById<MaterialCardView>(R.id.aboutRepoCard).setOnClickListener {
            openUrl(getString(R.string.about_repo_url))
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, url, Toast.LENGTH_LONG).show()
        }
    }
}
