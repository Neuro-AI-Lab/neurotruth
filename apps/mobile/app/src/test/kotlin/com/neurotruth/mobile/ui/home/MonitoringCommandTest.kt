package com.neurotruth.mobile.ui.home

import com.neurotruth.mobile.core.WatchConnectionState
import com.neurotruth.mobile.service.MeasurementControlStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class MonitoringCommandTest {

    @Test
    fun `connected watch alone does not start monitoring`() {
        assertEquals(
            MonitoringCommand.NONE,
            monitoringCommand(
                watchState = WatchConnectionState.CONNECTED,
                canUploadBiosignal = true,
                watchStatus = MeasurementControlStatus.STOPPED,
                isRunning = false,
            ),
        )
    }

    @Test
    fun `matching watch started acknowledgement starts monitoring`() {
        assertEquals(
            MonitoringCommand.START,
            monitoringCommand(
                watchState = WatchConnectionState.CONNECTED,
                canUploadBiosignal = true,
                watchStatus = MeasurementControlStatus.STARTED,
                isRunning = false,
            ),
        )
    }

    @Test
    fun `confirmed disconnect stops running monitoring`() {
        assertEquals(
            MonitoringCommand.STOP,
            monitoringCommand(
                watchState = WatchConnectionState.DISCONNECTED,
                canUploadBiosignal = true,
                watchStatus = MeasurementControlStatus.STARTED,
                isRunning = true,
            ),
        )
    }

    @Test
    fun `checking and query errors preserve current monitoring`() {
        listOf(WatchConnectionState.CHECKING, WatchConnectionState.ERROR).forEach { state ->
            assertEquals(
                MonitoringCommand.NONE,
                monitoringCommand(
                    state,
                    canUploadBiosignal = true,
                    watchStatus = MeasurementControlStatus.STARTED,
                    isRunning = true,
                ),
            )
        }
    }

    @Test
    fun `withdrawn biosignal consent stops monitoring`() {
        assertEquals(
            MonitoringCommand.STOP,
            monitoringCommand(
                watchState = WatchConnectionState.CONNECTED,
                canUploadBiosignal = false,
                watchStatus = MeasurementControlStatus.STARTED,
                isRunning = true,
            ),
        )
    }
}
