package net.osmand.plus.plugins.externalsensors

import android.app.Activity
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.osmand.plus.OsmandApplication
import net.osmand.plus.activities.MapActivity
import net.osmand.plus.plugins.PluginsHelper
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DevicesHelperPermissionTest {
    @Test
    fun permissionRevokedAfterPreflightReturnsFalse() {
        ActivityScenario.launch(MapActivity::class.java).use { scenario ->
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OsmandApplication
            val deadline = SystemClock.elapsedRealtime() + 30_000
            while (app.isApplicationInitializing && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(100)
            }
            assertFalse("Application initialization timed out", app.isApplicationInitializing)
            scenario.onActivity {
                val plugin = requireNotNull(PluginsHelper.getPlugin(ExternalSensorsPlugin::class.java))
                val helper = ExternalSensorsPlugin::class.java.getDeclaredField("devicesHelper")
                    .apply { isAccessible = true }.get(plugin) as DevicesHelper
                val owner = DevicesHelper::class.java.getDeclaredField("activity").apply { isAccessible = true }
                val previous = owner.get(helper)
                var platformCallReached = false
                val revoked = object : Activity() {
                    override fun checkPermission(permission: String, pid: Int, uid: Int) = PackageManager.PERMISSION_GRANTED

                    override fun getPackageManager(): PackageManager {
                        platformCallReached = true
                        throw SecurityException("Permission revoked after preflight")
                    }
                }
                try {
                    owner.set(helper, revoked)
                    assertFalse(helper.requestBLE())
                    assertTrue("The post-permission platform call was exercised", platformCallReached)
                } finally {
                    owner.set(helper, previous)
                }
            }
        }
    }
}
