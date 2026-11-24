package id.ac.ceh.data

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import com.google.firebase.firestore.Source
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.tasks.await

internal class CloudAccountGateway private constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
) {
    val uid: String?
        get() = auth.currentUser?.uid

    val sessions: Flow<String?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            trySend(firebaseAuth.currentUser?.uid)
        }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }.distinctUntilChanged()

    suspend fun authenticate(email: String, password: String, register: Boolean) {
        if (register) {
            auth.createUserWithEmailAndPassword(email, password).await()
        } else {
            auth.signInWithEmailAndPassword(email, password).await()
        }
    }

    fun signOut() {
        auth.signOut()
    }

    suspend fun readDisplayName(uid: String): String? {
        checkOwner(uid)
        val snapshot = firestore.collection("users").document(uid).get(Source.SERVER).await()
        checkOwner(uid)
        return snapshot.getString("displayName")
    }

    suspend fun writeDisplayName(uid: String, name: String) {
        checkOwner(uid)
        require(name.codePointCount(0, name.length) in 1..80) {
            "Nama tampilan harus berisi 1–80 karakter."
        }
        val profile = firestore.collection("users").document(uid)
        // Transaksi gagal saat luring, bukan mengantrekan perubahan untuk dikirim nanti.
        firestore.runTransaction { transaction ->
            checkOwner(uid)
            transaction.set(profile, mapOf("displayName" to name))
            Unit
        }.await()
    }

    private fun checkOwner(uid: String) {
        check(this.uid == uid) { "Sesi aktif tidak cocok dengan pemilik profil." }
    }

    companion object {
        @Synchronized
        fun create(context: Context): CloudAccountGateway? {
            val app = FirebaseApp.initializeApp(context.applicationContext) ?: return null
            val firestore = FirebaseFirestore.getInstance(app)
            if (firestore.firestoreSettings.cacheSettings !is MemoryCacheSettings) {
                firestore.firestoreSettings = FirebaseFirestoreSettings.Builder(firestore.firestoreSettings)
                    .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
                    .build()
            }
            return CloudAccountGateway(FirebaseAuth.getInstance(app), firestore)
        }
    }
}
