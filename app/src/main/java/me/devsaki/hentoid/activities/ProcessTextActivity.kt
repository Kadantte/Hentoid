package me.devsaki.hentoid.activities

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import me.devsaki.hentoid.core.AppStartup
import me.devsaki.hentoid.database.domains.Content
import me.devsaki.hentoid.enums.Site
import me.devsaki.hentoid.util.ContentHelper
import me.devsaki.hentoid.util.StringHelper
import timber.log.Timber


class ProcessTextActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppStartup.initApp(this,
            { f -> onMainProgress(f) },
            { f -> onSecondaryProgress(f) }
        ) { onInitComplete() }
    }

    private fun onMainProgress(f: Float) {
        Timber.i("Init @ ProcessTextActivity (main) : %s%%", f)
    }

    private fun onSecondaryProgress(f: Float) {
        Timber.i("Init @ ProcessTextActivity (secondary) : %s%%", f)
    }

    private fun onInitComplete() {
        val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT).toString()
        // process the text
        if (StringHelper.isNumeric(text)) {
            ContentHelper.launchBrowserFor(
                this, Content.getGalleryUrlFromId(Site.NHENTAI, text, -1)
            )
        }
        finish()
    }
}