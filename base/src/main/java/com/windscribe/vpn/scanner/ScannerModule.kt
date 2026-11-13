package com.windscribe.vpn.scanner

import androidx.room.Room
import com.windscribe.vpn.Windscribe
import com.windscribe.vpn.apppreference.PreferencesHelper
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Wiring for the network scanner. Its own module, so the app's modules stay as upstream has them. */
@Module
@InstallIn(SingletonComponent::class)
object ScannerModule {
    @Provides
    @Singleton
    fun provideScannerDatabase(app: Windscribe): ScannerDatabase =
        Room
            .databaseBuilder(app, ScannerDatabase::class.java, ScannerDatabase.NAME)
            // Scan results are disposable: a rescan rebuilds them.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    @Singleton
    fun provideScanResultDao(database: ScannerDatabase): ScanResultDao = database.scanResultDao()

    @Provides
    @Singleton
    fun provideProbeRunner(preferencesHelper: PreferencesHelper): ProbeRunner =
        ProbeRunner(
            probers =
                listOf(
                    TcpTlsProber(defaultSni = { preferencesHelper.tlsServerName }),
                    IkeProber(),
                ),
        )

    @Provides
    @Singleton
    fun provideTrafficProbe(app: Windscribe): TrafficProbe = JavaNetTrafficProbe(vpnNetwork = { JavaNetTrafficProbe.findVpnNetwork(app) })
}
