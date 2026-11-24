package id.ac.ceh.data

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CloudAccountLiveTest {
    @Test fun emailSessionsAndProfileIsolation(): Unit = runBlocking {
        // Uses real Firebase services and leaves at most one synthetic document for admin cleanup.
        assumeTrue(InstrumentationRegistry.getArguments().getString("cehCloudLive") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val gateway = checkNotNull(CloudAccountGateway.create(context)) { "Konfigurasi Firebase tidak tersedia." }
        val auth = FirebaseAuth.getInstance()
        val store = FirebaseFirestore.getInstance()
        check(auth.currentUser == null) { "Gunakan AVD uji tanpa sesi akun Firebase aktif." }
        val suffix = UUID.randomUUID().toString()
        val accountA = Fixture("ceh-smoke-a-$suffix@example.com")
        val accountB = Fixture("ceh-smoke-b-$suffix@example.com")
        val displayName = "CEH smoke ${suffix.take(8)}"
        val created = mutableListOf<Fixture>()
        var failed = false
        try {
            withTimeout(75_000) {
                gateway.authenticate(accountA.email, accountA.password, register = true)
                created += accountA
                val uidA = checkNotNull(gateway.sessions.first())
                assertNull(gateway.readDisplayName(uidA))
                gateway.writeDisplayName(uidA, displayName)
                Log.i(TAG, "Fixture profil untuk cleanup admin: $displayName")
                assertEquals(displayName, gateway.readDisplayName(uidA))
                gateway.signOut()
                assertNull(gateway.sessions.first())
                val profileA = store.collection("users").document(uidA)
                expectDenied { profileA.get(Source.SERVER).await() }

                gateway.authenticate(accountB.email, accountB.password, register = true)
                created += accountB
                val uidB = checkNotNull(gateway.sessions.first())
                assertNotEquals(uidA, uidB)
                assertNull(gateway.readDisplayName(uidB))
                expectDenied { profileA.get(Source.SERVER).await() }
                expectDenied {
                    store.runTransaction { tx ->
                        tx.set(profileA, mapOf("displayName" to "$displayName lintas akun"))
                        Unit
                    }.await()
                }
                gateway.signOut()

                gateway.authenticate(accountA.email, accountA.password, register = false)
                assertEquals(uidA, gateway.sessions.first())
                assertEquals(displayName, gateway.readDisplayName(uidA))
                Log.i(TAG, "PASS registrasi dua akun, logout/login ulang, profil server, dan penolakan tamu/lintas UID.")
            }
        } catch (e: Throwable) {
            failed = true
            // Provider exceptions can include identifiers; don't publish their text as evidence.
            if (e is kotlinx.coroutines.CancellationException) throw e
            val code = when (e) {
                is com.google.firebase.auth.FirebaseAuthException -> e.errorCode
                is FirebaseFirestoreException -> e.code.name
                else -> e.javaClass.simpleName
            }
            throw AssertionError("Uji Firebase live gagal: $code")
        } finally {
            val deleted = withContext(NonCancellable) {
                created.count { fixture ->
                    gateway.signOut()
                    try {
                        withTimeout(20_000) {
                            gateway.authenticate(fixture.email, fixture.password, register = false)
                            checkNotNull(auth.currentUser).delete().await()
                        }
                        true
                    } catch (_: Exception) {
                        false
                    }
                }.also { gateway.signOut() }
            }
            Log.i(TAG, "Akun sintetis Auth dihapus: $deleted/${created.size}")
            if (!failed) assertEquals(created.size, deleted)
        }
    }

    private suspend fun expectDenied(request: suspend () -> Unit) {
        val denied = try {
            request()
            false
        } catch (e: FirebaseFirestoreException) {
            assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, e.code)
            true
        }
        assertTrue("Aturan server harus menolak permintaan.", denied)
    }

    private class Fixture(val email: String, val password: String = UUID.randomUUID().toString())

    private companion object { const val TAG = "CEH-CloudLive" }
}
