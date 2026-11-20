package io.nekohasekai.sagernet.ui.profiles

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.GroupRepo
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.TestShowItems
import io.nekohasekai.sagernet.group.SubscriptionClient
import io.nekohasekai.sagernet.ktx.snackbar
import io.nekohasekai.sagernet.ui.ConfigurationFragment

/** The desktop's group actions on the current tab (mainwindow_profiles.cpp, mainwindow_setup.cpp). */
internal object GroupActions {

    fun updateSubscription(group: ProxyGroup) {
        if (group.isSubscription) SubscriptionClient.refreshGroup(group.id, showDiff = true)
    }

    /** BatchDeleteProfiles with the caller's stopRunningProfile; a kept running profile is reported. */
    fun deleteProfiles(host: ConfigurationFragment, ids: List<Long>, stopRunning: Boolean) {
        host.launchIo {
            val outcome = ProfileManager.batchDeleteProfiles(ids, stopRunning)
            host.onUi {
                val message = when {
                    !outcome.ok -> getString(R.string.profiles_delete_failed)
                    outcome.kept.isNotEmpty() -> getString(R.string.profiles_running_kept)
                    else -> resources.getQuantityString(R.plurals.removed, outcome.deleted.size, outcome.deleted.size)
                }
                snackbar(message).show()
            }
        }
    }

    /** The test result column's "Include: Out IP / Speed" (updateTestItemsToShow). */
    fun setShownItems(host: ConfigurationFragment, groupId: Long, outIp: Boolean, speed: Boolean) {
        val value = when {
            outIp && speed -> TestShowItems.ALL
            outIp -> TestShowItems.IP_ONLY
            speed -> TestShowItems.SPEED_ONLY
            else -> TestShowItems.NONE
        }.value
        host.launchIo {
            GroupFields.update(groupId) { it.testItemsToShow = value }?.let { GroupRepo.postUpdate(it) }
        }
    }
}
