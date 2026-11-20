package io.nekohasekai.sagernet.ui.profiles

import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.core.view.MenuCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.test.TestSpec
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.TestShowItems
import io.nekohasekai.sagernet.ui.ConfigurationFragment
import io.nekohasekai.sagernet.ui.test.TestSessionClient

/** The ⋮ menu of the profiles screen: actions on the current tab's group (the desktop's Groups menu). */
class GroupMenu(private val host: ConfigurationFragment) {

    fun setUp(menu: Menu) {
        menu.findItem(R.id.action_misc)?.subMenu?.let { MenuCompat.setGroupDividerEnabled(it, true) }
        keepOpen(menu, R.id.action_show_out_ip) { toggleShown(menu, it) }
        keepOpen(menu, R.id.action_show_speed) { toggleShown(menu, it) }
    }

    /**
     * A toggle that leaves its popup open: MenuBuilder.performItemAction closes the menu after a click unless the
     * item has a collapsible action view whose expansion is refused.
     */
    private fun keepOpen(menu: Menu, id: Int, onClick: (MenuItem) -> Unit) {
        val item = menu.findItem(id) ?: return
        item.actionView = View(host.requireContext())
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW)
        item.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem) = false
            override fun onMenuItemActionCollapse(item: MenuItem) = true
        })
        item.setOnMenuItemClickListener {
            onClick(it)
            false
        }
    }

    private fun toggleShown(menu: Menu, item: MenuItem) {
        item.isChecked = !item.isChecked
        val group = host.currentGroup() ?: return
        GroupActions.setShownItems(
            host,
            group.id,
            outIp = menu.findItem(R.id.action_show_out_ip)?.isChecked == true,
            speed = menu.findItem(R.id.action_show_speed)?.isChecked == true,
        )
    }

    /** Before the ⋮ menu opens: what applies to the current group and the running test / service. */
    fun prepare(menu: Menu) {
        val group = host.currentGroup()
        menu.findItem(R.id.action_update_subscription)?.isVisible = group?.isSubscription == true
        menu.findItem(R.id.action_test_stop)?.isVisible = host.testState.running
        if (group != null) {
            val shown = TestShowItems.of(group.testItemsToShow)
            menu.findItem(R.id.action_show_out_ip)?.isChecked = shown.showIp
            menu.findItem(R.id.action_show_speed)?.isChecked = shown.showSpeed
        }
    }

    fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_test_stop -> TestSessionClient.stop()
            R.id.action_layout -> host.setDoubleColumn(!host.doubleColumn)
            else -> return onGroupItemClick(item, host.currentGroup() ?: return false)
        }
        return true
    }

    private fun onGroupItemClick(item: MenuItem, group: ProxyGroup): Boolean {
        val id = group.id
        when (item.itemId) {
            R.id.action_update_subscription -> GroupActions.updateSubscription(group)
            R.id.action_test_url -> ProfileTests.startGroup(host, TestSpec.KIND_URL, id)
            R.id.action_test_ip -> ProfileTests.startGroup(host, TestSpec.KIND_IP, id)
            R.id.action_test_speed -> ProfileTests.startGroup(host, TestSpec.KIND_SPEED, id)
            R.id.action_test_service -> ProfileTests.startServiceGroup(host, id)
            R.id.action_test_quality -> ProfileTests.startGroup(host, TestSpec.KIND_QUALITY, id)
            else -> return false
        }
        return true
    }
}
