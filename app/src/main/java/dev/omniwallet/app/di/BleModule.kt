package dev.omniwallet.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.omniwallet.device.chameleon.ChameleonDevice
import dev.omniwallet.device.flipper.FlipperDevice
import dev.omniwallet.transport.ble.BleScanner
import dev.omniwallet.transport.ble.ScanTarget
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object BleModule {

    /**
     * What discovery looks for.
     *
     * Assembled here rather than inside the scanner so `:transport:ble` stays
     * device-agnostic: adding a third backend is a line in this list, not a
     * change to the transport. Each device contributes its own pattern, since
     * only the backend knows what its hardware advertises -- and for the
     * Flipper that is emphatically not the service it later serves.
     */
    @Provides
    @Singleton
    fun provideScanTargets(): List<@JvmSuppressWildcards ScanTarget> = listOf(
        FlipperDevice.SCAN_TARGET,
        ChameleonDevice.SCAN_TARGET,
    )

    @Provides
    @Singleton
    fun provideScanner(
        @ApplicationContext context: Context,
        targets: List<@JvmSuppressWildcards ScanTarget>,
    ): BleScanner = BleScanner(context, targets)
}
