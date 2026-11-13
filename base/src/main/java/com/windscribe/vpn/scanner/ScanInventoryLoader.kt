package com.windscribe.vpn.scanner

import com.windscribe.vpn.localdatabase.LocalDbInterface
import com.windscribe.vpn.repository.PortMapRepository
import com.windscribe.vpn.repository.UnblockWgParamsRepository
import com.windscribe.vpn.repository.UserRepository
import com.windscribe.vpn.serverlist.entity.DatacenterStatus
import com.windscribe.vpn.serverlist.entity.DatacenterStatusHelper
import dagger.Lazy
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads what a scan can try from the cached server list, port map and AmneziaWG presets. Nothing
 * here touches the network, so it works with the VPN down and a filtered connection.
 */
@Singleton
class ScanInventoryLoader
    @Inject
    constructor(
        private val localDb: LocalDbInterface,
        private val portMapRepository: PortMapRepository,
        private val userRepository: Lazy<UserRepository>,
        private val unblockWgParamsRepository: UnblockWgParamsRepository,
    ) {
        private val logger = LoggerFactory.getLogger(SCANNER_LOG)

        suspend fun load(): ScanInventory {
            val user = userRepository.get().user.value
            val isPro = user?.isPro ?: false
            val favourites = localDb.getFavouritesAsync().mapTo(HashSet()) { it.id }
            // Non-static pings are keyed by datacenter id. Zero or less means no usable reading.
            val pings =
                localDb
                    .getAllPingsAsync()
                    .filter { !it.isStatic && it.pingTime > 0 }
                    .associate { it.ping_id to it.pingTime }
            val cities = ArrayList<ScanCity>()
            for (location in localDb.getAllLocationsAsync()) {
                val hasAlc = user?.hasAlcAccess(location.location?.countryCode) ?: false
                for (datacenter in location.datacenters) {
                    val servers = localDb.getServersByDatacenter(datacenter.id).filter { it.isConnectable }
                    if (DatacenterStatusHelper.getStatus(datacenter, servers.size, isPro, hasAlc) != DatacenterStatus.Available) {
                        continue
                    }
                    cities.add(
                        ScanCity(
                            id = datacenter.id,
                            name = datacenter.nodeName ?: datacenter.nickName ?: datacenter.id.toString(),
                            nodes = servers.map { ScanNode(it.hostname, it.ip, it.ip2, it.ip3) },
                            isFavourite = datacenter.id in favourites,
                            pingMs = pings[datacenter.id],
                        ),
                    )
                }
            }
            val portMap =
                portMapRepository
                    .getPortMap()
                    .getOrNull()
                    ?.portmap
                    .orEmpty()
                    .mapNotNull { entry ->
                        val protocol = entry.protocol ?: return@mapNotNull null
                        protocol to entry.ports.orEmpty().mapNotNull { it.toIntOrNull() }
                    }.toMap()
            val presets = unblockWgParamsRepository.unblockWgParams.value.map { it.id }
            logger.info(
                "Inventory: {} cities, {} protocols in the port map, {} AmneziaWG presets.",
                cities.size,
                portMap.size,
                presets.size,
            )
            return ScanInventory(cities, portMap, presets)
        }
    }
