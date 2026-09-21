package dev.omniwallet.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.omniwallet.app.quick.WidgetUpdater
import javax.inject.Inject

@HiltAndroidApp
class OmniWalletApplication : Application() {

    /**
     * Started here because Hilt singletons are lazy and nothing else would ask
     * for this one. The widget lives outside every screen, so there is no
     * ViewModel whose lifetime could own keeping it current.
     */
    @Inject
    lateinit var widgetUpdater: WidgetUpdater

    override fun onCreate() {
        super.onCreate()
        widgetUpdater.start()
    }
}
