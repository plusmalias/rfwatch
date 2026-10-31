package com.rfwatch.app.di

import android.content.Context
import com.rfwatch.app.alerts.AlertManager
import com.rfwatch.app.data.RfWatchDatabase
import com.rfwatch.app.repo.RfWatchRepository
import com.rfwatch.app.scan.BleScanner
import com.rfwatch.app.scan.CellScanner
import com.rfwatch.app.scan.RttRanger
import com.rfwatch.app.scan.WifiScanner
import com.rfwatch.app.sensors.HeadingProvider
import com.rfwatch.app.sensors.LocationProvider
import com.rfwatch.core.rules.RuleEngine
import com.rfwatch.core.rules.RulePacks

/**
 * Hand-rolled dependency graph — the app is small enough that a DI framework would be
 * overhead. Everything is lazy so construction is cheap and the database (which touches the
 * Keystore) is only opened when first needed.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val database: RfWatchDatabase by lazy { RfWatchDatabase.create(appContext) }
    val alertManager: AlertManager by lazy { AlertManager(appContext) }
    val headingProvider: HeadingProvider by lazy { HeadingProvider(appContext) }
    val locationProvider: LocationProvider by lazy { LocationProvider(appContext) }
    val bleScanner: BleScanner by lazy { BleScanner(appContext) }
    val wifiScanner: WifiScanner by lazy { WifiScanner(appContext) }
    val rttRanger: RttRanger by lazy { RttRanger(appContext) }
    val cellScanner: CellScanner by lazy { CellScanner(appContext) }

    /** Rule engine built from the bundled packs (all enabled by default). */
    val ruleEngine: RuleEngine by lazy { RuleEngine(RulePacks.loadBuiltin()) }

    val repository: RfWatchRepository by lazy {
        RfWatchRepository(
            database = database,
            alertManager = alertManager,
            ruleEngine = ruleEngine,
            bleScanner = bleScanner,
            wifiScanner = wifiScanner,
            rttRanger = rttRanger,
            cellScanner = cellScanner,
            headingProvider = headingProvider,
            locationProvider = locationProvider,
        )
    }
}
