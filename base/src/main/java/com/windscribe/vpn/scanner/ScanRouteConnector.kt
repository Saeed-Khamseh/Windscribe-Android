package com.windscribe.vpn.scanner

import com.windscribe.vpn.apppreference.PreferencesHelper
import com.windscribe.vpn.autoconnection.AutoConnectionManager
import com.windscribe.vpn.autoconnection.PreferredRoute
import com.windscribe.vpn.backend.Util
import com.windscribe.vpn.backend.utils.ConnectSource
import com.windscribe.vpn.backend.utils.LastSelectedLocation
import com.windscribe.vpn.commonutils.WindUtilities
import com.windscribe.vpn.localdatabase.LocalDbInterface
import com.windscribe.vpn.model.User
import com.windscribe.vpn.repository.LocationRepository
import com.windscribe.vpn.repository.UserRepository
import com.windscribe.vpn.serverlist.entity.DatacenterStatus
import com.windscribe.vpn.serverlist.entity.DatacenterStatusHelper
import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Singleton

/** What [ScanRouteConnector.connect] did. Everything but [Started] left the app untouched. */
sealed interface ConnectOutcome {
    /** The connect was handed to the normal user connect path; progress shows on the home screen. */
    data object Started : ConnectOutcome

    /** A scan is running and owns the connect path. */
    data object ScanRunning : ConnectOutcome

    data object NoNetwork : ConnectOutcome

    /** The account is expired or banned, which the home screen handles with its own screens. */
    data object AccountBlocked : ConnectOutcome

    /** The city is gone from the server list, down, or needs a plan the user does not have. */
    data object LocationUnavailable : ConnectOutcome
}

/**
 * "Connect using this result": the same as the user tapping the city on the home screen, except that
 * the result's protocol and port are tried first, on the result's node.
 *
 * The result's tweak is written to the real preferences for good, because the user chose it. The
 * connect itself is `AutoConnectionManager.connectInForeground(User)` with a [PreferredRoute], so
 * if the route fails the app's ordinary failover takes over after it.
 *
 * Meant to be reused for "try the saved route first" on later connects.
 */
@Singleton
class ScanRouteConnector
    @Inject
    constructor(
        private val prefs: PreferencesHelper,
        private val localDb: LocalDbInterface,
        private val locationRepository: Lazy<LocationRepository>,
        private val userRepository: Lazy<UserRepository>,
        private val autoConnectionManager: AutoConnectionManager,
        private val scope: CoroutineScope,
    ) {
        private val logger = LoggerFactory.getLogger(SCANNER_LOG)

        suspend fun connect(result: ScanResult): ConnectOutcome {
            val candidate = result.candidate
            val user = userRepository.get().user.value
            val city = withContext(Dispatchers.IO) { localDb.getDatacenterAndLocation(candidate.cityId) }
            val servers =
                if (city == null) {
                    emptyList()
                } else {
                    withContext(Dispatchers.IO) { localDb.getServersByDatacenter(candidate.cityId) }.filter { it.isConnectable }
                }
            val status =
                city?.let {
                    DatacenterStatusHelper.getStatus(
                        it.datacenter,
                        servers.size,
                        user?.isPro ?: false,
                        user?.hasAlcAccess(it.location?.countryCode) ?: false,
                    )
                }
            val refusal =
                refusalFor(
                    scanActive = ScanSession.active,
                    online = WindUtilities.isOnline(),
                    accountStatus = user?.accountStatus,
                    datacenterStatus = status,
                )
            if (refusal != null || city == null) {
                logger.info("Not connecting to {}: {}", candidate, refusal ?: ConnectOutcome.LocationUnavailable)
                return refusal ?: ConnectOutcome.LocationUnavailable
            }

            val route = routeFor(candidate, servers.map { it.hostname })
            logger.info("Connecting with scan result {} as {}", candidate, route)
            // Persistently: this is the user's choice, not a measurement.
            ScanTweakApplier.apply(prefs, candidate.tweak)
            if (candidate.tweak != ScanTweak.None) prefs.isAntiCensorshipManualMode = true

            // What the home screen's city tap does before it connects.
            prefs.globalUserConnectionPreference = true
            prefs.isConnectingToStaticIp = false
            prefs.isConnectingToConfigured = false
            val coordinates = (city.datacenter.coordinates ?: "").split(",")
            Util.saveSelectedLocation(
                LastSelectedLocation(
                    city.datacenter.id,
                    city.datacenter.nodeName ?: "",
                    city.datacenter.nickName ?: "",
                    city.location?.countryCode,
                    coordinates.getOrNull(0).orEmpty(),
                    coordinates.getOrNull(1).orEmpty(),
                ),
            )
            locationRepository.get().setSelectedCity(city.datacenter.id)
            // Application scope, like the home screen: the connect outlives whichever screen asked.
            scope.launch { autoConnectionManager.connectInForeground(ConnectSource.User, route) }
            return ConnectOutcome.Started
        }

        companion object {
            /**
             * The route to try first. The node is kept only if the city still has it: after a server
             * list refresh the saved node may be gone, and then any node of the city will do.
             */
            internal fun routeFor(
                candidate: ScanCandidate,
                cityHostnames: Collection<String>,
            ): PreferredRoute =
                PreferredRoute(
                    protocol = candidate.protocol,
                    port = candidate.port.toString(),
                    hostname = candidate.nodeHostname.takeIf { wanted -> cityHostnames.any { WindUtilities.hostnamesMatch(it, wanted) } },
                )

            /** Why a connect must not start, or null. [datacenterStatus] is null when the city is not in the database. */
            internal fun refusalFor(
                scanActive: Boolean,
                online: Boolean,
                accountStatus: User.AccountStatus?,
                datacenterStatus: DatacenterStatus?,
            ): ConnectOutcome? =
                when {
                    scanActive -> ConnectOutcome.ScanRunning
                    !online -> ConnectOutcome.NoNetwork
                    accountStatus == User.AccountStatus.Expired || accountStatus == User.AccountStatus.Banned -> ConnectOutcome.AccountBlocked
                    datacenterStatus != DatacenterStatus.Available -> ConnectOutcome.LocationUnavailable
                    else -> null
                }
        }
    }
