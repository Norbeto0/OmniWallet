package dev.omniwallet.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.omniwallet.core.domain.DeviceKind
import dev.omniwallet.device.chameleon.ChameleonDevice
import dev.omniwallet.device.flipper.FlipperDevice
import dev.omniwallet.transport.ble.BleScanner
import java.util.UUID
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object BleModule {

    /**
     * The service UUIDs worth scanning for, and what each one means.
     *
     * Assembled here rather than inside the scanner so `:transport:ble` stays
     * device-agnostic: adding a third backend is a line in this map, not a
     * change to the transport.
     */
    @Provides
    @Singleton
    fun provideKnownServices(): Map<UUID, @JvmSuppressWildcards DeviceKind> = mapOf(
        FlipperDevice.SERIAL_SERVICE to DeviceKind.FLIPPER_ZERO,
        ChameleonDevice.NORDIC_UART_SERVICE to DeviceKind.CHAMELEON_ULTRA,
    )

    @Provides
    @Singleton
    fun provideScanner(
        @ApplicationContext context: Context,
        knownServices: Map<UUID, @JvmSuppressWildcards DeviceKind>,
    ): BleScanner = BleScanner(context, knownServices)
}
