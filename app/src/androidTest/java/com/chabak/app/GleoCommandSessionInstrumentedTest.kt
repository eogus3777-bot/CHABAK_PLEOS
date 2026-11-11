package com.chabak.app

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class GleoCommandSessionInstrumentedTest {
    private class Replies : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
        val sent = LinkedBlockingQueue<Intent>()
        override fun sendBroadcast(intent: Intent) { sent.offer(intent) }
        fun take(): JSONObject {
            val intent = sent.poll(2, TimeUnit.SECONDS)
            assertNotNull("A callback must be returned", intent)
            assertEquals(GleoContract.RESPONSE_ACTION, intent!!.action)
            assertEquals(packageName, intent.getStringExtra("packageName"))
            return JSONObject(intent.getStringExtra("response")!!).also {
                assertEquals("voice", it.getString("mode"))
                assertTrue(it.getString("status") in setOf("success", "asking", "fail"))
                assertTrue(it.get("data") is String)
                assertEquals(1, JSONArray(it.getString("data")).length())
            }
        }
    }

    private val completed = GleoResponse("success", "The command completed.", "FOUND")
    private fun reason(reply: JSONObject) = JSONArray(reply.getString("data")).getJSONObject(0).getString("reason")

    @Test fun duplicatesCoalesceAcrossTypedAndJsonEnvelopesOnlyWhileInFlight() {
        val replies = Replies()
        val session = GleoCommandSession(replies)
        try {
            val typed = Intent(GleoCampingAction.ACTION).putExtra("region", "경기").putExtra("electric", true)
            session.submit(typed)
            val request = session.requests.value!!
            val raw = JSONObject().put("electric", true).put("region", "경기").toString()
            session.submit(Intent(GleoCampingAction.ACTION).putExtra("ACTION_PARAM", raw))
            session.submit(Intent(GleoCampingAction.ACTION).putExtra("ACTION_PARAM", JSONObject.quote(raw)))
            assertSame(request, session.requests.value)
            assertTrue(replies.sent.isEmpty())
            session.complete(request, completed)
            assertEquals("success", replies.take().getString("status"))
            session.complete(request, completed)
            assertTrue(replies.sent.isEmpty())
            // The same words may be a legitimate later command; no undocumented request ID is assumed.
            session.submit(typed)
            assertNotSame(request, session.requests.value)
            session.complete(session.requests.value!!, completed)
            replies.take()
        } finally { session.close() }
    }

    @Test fun aDifferentOverlappingCommandFailsWithoutReplacingThePendingOperation() {
        val replies = Replies()
        val session = GleoCommandSession(replies)
        try {
            session.submit(Intent(GleoTripAction.MANAGE).putExtra("operation", "READ_ENERGY"))
            val request = session.requests.value!!
            session.submit(Intent(GleoTripAction.MANAGE).putExtra("operation", "START_STAY"))
            val rejected = replies.take()
            assertEquals("fail", rejected.getString("status"))
            assertEquals("REQUEST_IN_PROGRESS", reason(rejected))
            assertSame(request, session.requests.value)
            session.complete(request, completed)
            replies.take()
        } finally { session.close() }
    }

    @Test fun timeoutIsBoundedAndLateOrRepeatedSuccessCannotEscape() {
        val replies = Replies()
        val session = GleoCommandSession(replies, timeoutMillis = 80)
        try {
            val started = SystemClock.uptimeMillis()
            session.submit(Intent(GleoCampingAction.ACTION).putExtra("region", "전국"))
            val request = session.requests.value!!
            val response = replies.take()
            assertEquals("fail", response.getString("status"))
            assertEquals("TIMEOUT", reason(response))
            assertTrue(SystemClock.uptimeMillis() - started < 2_000)
            assertNull(session.requests.value)
            repeat(2) { session.complete(request, completed) }
            assertNull(replies.sent.poll(150, TimeUnit.MILLISECONDS))
        } finally { session.close() }
    }

    @Test fun closingFailsExactlyOnceAndIgnoresOrdinaryLauncherIntents() {
        val replies = Replies()
        val session = GleoCommandSession(replies)
        session.submit(Intent(Intent.ACTION_MAIN))
        assertNull(session.requests.value)
        assertTrue(replies.sent.isEmpty())
        session.submit(Intent(GleoTripAction.SEARCH).putExtra("category", "MARKET"))
        val request = session.requests.value!!
        session.close()
        assertEquals("APP_CLOSED", reason(replies.take()))
        session.close()
        session.complete(request, completed)
        assertTrue(replies.sent.isEmpty())
    }

    @Test fun expiredCommandsCannotStartEvenWhenTheMainThreadDelaysTheTimer() {
        val replies = Replies()
        val session = GleoCommandSession(replies, timeoutMillis = 40)
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                session.submit(Intent(GleoTripAction.MANAGE).putExtra("operation", "START_STAY"))
                val request = session.requests.value!!
                // Simulate startup contention: the main-thread timer cannot run until this returns.
                SystemClock.sleep(80)
                assertFalse(session.canExecute(request))
                assertNull(session.requests.value)
            }
            assertEquals("TIMEOUT", reason(replies.take()))
            assertNull(replies.sent.poll(100, TimeUnit.MILLISECONDS))
        } finally { session.close() }
    }

    @Test fun malformedTypedAndJsonParametersNeverInvokeTheCampingService() {
        val replies = Replies()
        val cases = listOf(
            Intent(GleoCampingAction.ACTION).putExtra("region", "경기").putExtra("electric", "true"),
            Intent(GleoCampingAction.ACTION).putExtra("region", 42),
            Intent(GleoCampingAction.ACTION).putExtra("ACTION_PARAM", 42),
            Intent(GleoCampingAction.ACTION).putExtra("ACTION_PARAM", "[]"),
            Intent(GleoCampingAction.ACTION).putExtra("ACTION_PARAM", "x".repeat(4097)),
            Intent(GleoCampingAction.ACTION).putExtra("region", "경기").putExtra("district", "가평\n" + "x"),
            Intent(GleoCampingAction.ACTION).putExtra("region", "경기").putExtra("nature", "unknown")
        )
        for (intent in cases) {
            GleoCampingAction.handle(replies, intent) { _, _ -> fail("Invalid input must not search") }
            assertEquals("INVALID_PARAMETERS", reason(replies.take()))
        }
    }

    @Test fun campingForwardsAllSixParametersAndSendsOnlyOneCallback() {
        val replies = Replies()
        val raw = JSONObject().put("region", "경기").put("district", "가평").put("nature", "숲")
            .put("toilet", true).put("electric", true).put("store", true).toString()
        GleoCampingAction.handle(replies, Intent(GleoCampingAction.ACTION).putExtra("ACTION_PARAM", raw)) { query, complete ->
            assertEquals(GleoCampingAction.Query("경기", "가평", "숲", true, true, true), query)
            complete(GleoCampingAction.Outcome.FOUND, listOf("isolated test campsite"))
            complete(GleoCampingAction.Outcome.SERVICE_FAILURE, emptyList())
        }
        assertEquals("FOUND", reason(replies.take()))
        assertTrue(replies.sent.isEmpty())
        GleoCampingAction.handle(replies, Intent(GleoCampingAction.ACTION).putExtra("region", "전국")) { _, complete ->
            complete(GleoCampingAction.Outcome.NO_RESULTS, emptyList())
        }
        assertEquals("success", replies.take().getString("status"))
        GleoCampingAction.handle(replies, Intent(GleoCampingAction.ACTION).putExtra("region", "전국")) { _, _ ->
            throw IllegalStateException("isolated service failure")
        }
        assertEquals("SERVICE_FAILURE", reason(replies.take()))
    }

    @Test fun transportFailureDoesNotCrashTheActivity() {
        val denied = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun sendBroadcast(intent: Intent) { throw SecurityException("isolated denial") }
        }
        GleoContract.reply(denied, GleoTripAction.MANAGE, completed)
    }
}
