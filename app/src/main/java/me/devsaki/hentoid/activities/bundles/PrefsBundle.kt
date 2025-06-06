package me.devsaki.hentoid.activities.bundles

import android.os.Bundle
import me.devsaki.hentoid.enums.Site
import me.devsaki.hentoid.util.boolean
import me.devsaki.hentoid.util.int

/**
 * Helper class to transfer data from any Activity to [me.devsaki.hentoid.activities.prefs.PreferencesActivity]
 * through a Bundle
 */
class PrefsBundle(val bundle: Bundle = Bundle()) {

    var isViewerPrefs by bundle.boolean(default = false)

    var isBrowserPrefs by bundle.boolean(default = false)

    var isDownloaderPrefs by bundle.boolean(default = false)

    var isStoragePrefs by bundle.boolean(default = false)

    var site by bundle.int(default = Site.NONE.code)
}