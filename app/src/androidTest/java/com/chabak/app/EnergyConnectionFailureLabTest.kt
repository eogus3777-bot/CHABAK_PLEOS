package com.chabak.app

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class EnergyConnectionFailureLabTest {
    @Test
    fun reportsBindingFailureWithoutStoppingTheSystemVehicleService() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(
            "Run with the isolated Pleos emulator permission overlay",
            context.checkSelfPermission("pleos.car.permission.CAR_ENERGY") == PackageManager.PERMISSION_GRANTED
        )
        val bindingAttempted = AtomicBoolean(false)
        val blocked = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean {
                bindingAttempted.set(true)
                return false
            }
        }
        val failure = CompletableDeferred<EnergyRead<Int>>()
        val job = launch(Dispatchers.Main) {
            PleosEnergyReader(blocked).monitor { result ->
                if (result.soc != EnergyRead.Missing(EnergyIssue.LOADING)) failure.complete(result.soc)
            }
        }
        try {
            assertEquals(EnergyRead.Missing(EnergyIssue.CONNECTION), withTimeout(12_000) { failure.await() })
            assertTrue(bindingAttempted.get())
        } finally {
            job.cancelAndJoin()
        }
    }
}
