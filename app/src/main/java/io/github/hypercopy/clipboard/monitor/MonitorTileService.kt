package io.github.hypercopy.clipboard.monitor

import android.content.Context
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.hypercopy.R
import io.github.hypercopy.data.settings.SettingsRepository

/**
 * 快捷磁贴（v1.30）：下拉状态栏一键暂停/恢复剪贴板监听直达。
 * 框架 API（android.service.quicksettings.TileService），无需额外依赖。
 */
class MonitorTileService : TileService() {
    private val settings: SettingsRepository
        get() = SettingsRepository(applicationContext)

    override fun onClick() {
        val enabled = !settings.readMonitorEnabled()
        settings.persistMonitorEnabled(enabled)
        updateTile(enabled)
        // 兼容 Shizuku 悬浮窗/无障碍即时感知（刷新前台状态）
        io.github.hypercopy.HyperLog.d("HyperCopy", "tile toggle monitorEnabled=$enabled")
    }

    override fun onStartListening() {
        updateTile(settings.readMonitorEnabled())
    }

    private fun updateTile(enabled: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(if (enabled) R.string.tile_monitor_on else R.string.tile_monitor_off)
        tile.updateTile()
    }
}