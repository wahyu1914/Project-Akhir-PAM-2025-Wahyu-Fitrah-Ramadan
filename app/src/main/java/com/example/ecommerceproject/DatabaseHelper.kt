package com.example.ecommerceproject

import android.content.Context
import android.net.Uri
import android.util.Log
import com.cloudinary.android.MediaManager
import com.cloudinary.android.callback.ErrorInfo
import com.cloudinary.android.callback.UploadCallback
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.database.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Date

class DatabaseHelper {
    internal val database = FirebaseDatabase.getInstance("https://projectpam-de18d-default-rtdb.asia-southeast1.firebasedatabase.app/").reference
    internal val auth = FirebaseAuth.getInstance()

    object UserRole {
        const val ADMIN = "admin"
        const val PENGELOLA = "pengelola"
        const val SUPERVISOR = "supervisor"
        const val CUSTOMER = "customer"
        const val PIMPINAN = "pimpinan"
    }

    object UserLevel {
        const val ADMIN = "admin"
        const val PENGELOLA = "pengelola"
        const val SUPERVISOR = "supervisor"
        const val USER = "user"
        const val PIMPINAN = "pimpinan"
    }

    /**
     * BARU: Konstanta untuk status pemesanan yang terpusat.
     * Ini memastikan konsistensi status di seluruh aplikasi.
     */
    object OrderStatus {
        const val PENDING = "PENDING"
        const val DIKEMAS = "DIKEMAS"
        const val DIKIRIM = "DIKIRIM"
        const val DITERIMA = "DITERIMA"
        const val SELESAI = "SELESAI" // Setelah diberi rating
        const val DIBATALKAN = "DIBATALKAN"
    }


    companion object {
        private var isCloudinaryInitialized = false

        fun initCloudinary(context: Context) {
            if (!isCloudinaryInitialized) {
                val config = mapOf(
                    "cloud_name" to "degz75abl",
                    "api_key" to "892278659124576",
                    "api_secret" to "f5WUY4ZtiPLCUkX1fvUjnViAAr4"
                )
                try {
                    MediaManager.init(context, config)
                    isCloudinaryInitialized = true
                    Log.d("DatabaseHelper", "Cloudinary berhasil diinisialisasi dengan cloud_name: djwfibc4t")
                } catch (e: Exception) {
                    Log.e("DatabaseHelper", "Gagal menginisialisasi Cloudinary: ${e.message}", e)
                    throw IllegalStateException("Inisialisasi Cloudinary gagal")
                }
            } else {
                Log.w("DatabaseHelper", "Cloudinary sudah diinisialisasi, melewati inisialisasi ulang")
            }
        }
    }

    fun CurrentUserId(): String? {
        return auth.currentUser?.uid
    }

    private suspend fun uploadToCloudinary(
        uri: Uri?,
        folder: String,
        publicId: String,
        preset: String
    ): String {
        if (uri == null) {
            Log.e("DatabaseHelper", "Gagal mengunggah: URI kosong")
            throw IllegalArgumentException("URI gambar tidak boleh kosong")
        }
        Log.d("DatabaseHelper", "Mengunggah ke Cloudinary: folder=$folder, publicId=$publicId, uri=$uri")
        return suspendCancellableCoroutine { continuation ->
            MediaManager.get().upload(uri)
                .option("folder", folder)
                .option("upload_preset", preset)
                .option("public_id", publicId)
                .callback(object : UploadCallback {
                    override fun onStart(requestId: String) {
                        Log.d("DatabaseHelper", "Upload Cloudinary dimulai: requestId=$requestId")
                    }

                    override fun onProgress(requestId: String, bytes: Long, totalBytes: Long) {
                        Log.d("DatabaseHelper", "Progres upload Cloudinary: $bytes/$totalBytes")
                    }

                    override fun onSuccess(requestId: String, resultData: Map<*, *>) {
                        val url = resultData["secure_url"] as? String
                        if (url != null) {
                            Log.d("DatabaseHelper", "Upload Cloudinary berhasil: url=$url")
                            continuation.resume(url)
                        } else {
                            Log.e("DatabaseHelper", "Upload Cloudinary gagal: Tidak ada URL")
                            continuation.resumeWithException(Exception("Tidak ada URL dari Cloudinary"))
                        }
                    }

                    override fun onError(requestId: String, error: ErrorInfo) {
                        Log.e("DatabaseHelper", "Upload Cloudinary gagal: ${error.description}")
                        continuation.resumeWithException(Exception("Upload Cloudinary gagal: ${error.description}"))
                    }

                    override fun onReschedule(requestId: String, error: ErrorInfo) {
                        Log.w("DatabaseHelper", "Upload Cloudinary dijadwalkan ulang: ${error.description}")
                    }
                })
                .dispatch()
        }
    }

    suspend fun uploadProfilePhoto(userId: String, uri: Uri?): String {
        return uploadToCloudinary(uri, "profile_photos", "profile_$userId", "profile_photos")
    }

    suspend fun uploadHobbyPhoto(userId: String, uri: Uri?, hobbyIndex: Int): String {
        return uploadToCloudinary(uri, "hobby_photos", "hobby_${userId}_$hobbyIndex", "hobby_photos")
    }

    suspend fun uploadProductImage(productId: String, uri: Uri?): String {
        return uploadToCloudinary(uri, "product_images", "product_$productId", "product_images")
    }

    suspend fun isAdmin(): Boolean {
        val TAG = "DEBUG_ROLE_CHECK"
        Log.d(TAG, "Memulai pengecekan admin...")

        val userId = auth.currentUser?.uid
        if (userId == null) {
            Log.w(TAG, "User belum login")
            return false
        }

        try {
            auth.currentUser?.reload()?.await()
            val snapshot = database.child("users").child(userId).get().await()
            if (!snapshot.exists()) {
                Log.w(TAG, "[isAdmin] Tidak ada data profil di database untuk userId=$userId")
                return false
            }

            val profile = snapshot.value as? Map<String, Any>
            val role = profile?.get("role") as? String
            Log.d(TAG, "[isAdmin] Ditemukan role: $role untuk userId=$userId")

            return role == UserRole.ADMIN
        } catch (e: Exception) {
            Log.e(TAG, "[isAdmin] Error saat pengecekan admin: ${e.message}", e)
            return false
        }
    }

    suspend fun isSupervisor(): Boolean {
        val TAG = "DEBUG_ROLE_CHECK"
        Log.d(TAG, "Mencoba memeriksa isSupervisor...")
        try {
            val profile = getUserProfile(true)
            if (profile == null) {
                Log.w(TAG, "[isSupervisor] Profil pengguna null untuk userId=${auth.currentUser?.uid}")
                return false
            }
            Log.d(TAG, "[isSupervisor] Profil yang didapat: $profile")
            val roleFromProfile = profile["role"] as? String
            Log.d(TAG, "[isSupervisor] Role dari profil: '$roleFromProfile'")
            val isSupervisor = roleFromProfile == UserRole.SUPERVISOR
            Log.d(TAG, "[isSupervisor] Hasil pengecekan: $isSupervisor")
            return isSupervisor
        } catch (e: Exception) {
            Log.e(TAG, "[isSupervisor] Gagal memeriksa: ${e.message}", e)
            return false
        }
    }

    suspend fun isPengelola(): Boolean {
        val TAG = "DEBUG_ROLE_CHECK"
        Log.d(TAG, "Mencoba memeriksa isPengelola...")
        try {
            val profile = getUserProfile(true)
            if (profile == null) {
                Log.w(TAG, "[isPengelola] Profil pengguna null untuk userId=${auth.currentUser?.uid}")
                return false
            }
            Log.d(TAG, "[isPengelola] Profil yang didapat: $profile")
            val roleFromProfile = profile["role"] as? String
            Log.d(TAG, "[isPengelola] Role dari profil: '$roleFromProfile'")
            val isPengelola = roleFromProfile == UserRole.PENGELOLA
            Log.d(TAG, "[isPengelola] Hasil pengecekan: $isPengelola")
            return isPengelola
        } catch (e: Exception) {
            Log.e(TAG, "[isPengelola] Gagal memeriksa: ${e.message}", e)
            return false
        }
    }

    suspend fun isPimpinan(): Boolean {
        val TAG = "DEBUG_ROLE_CHECK"
        Log.d(TAG, "Mencoba memeriksa isPimpinan...")
        try {
            val profile = getUserProfile(true)
            if (profile == null) {
                Log.w(TAG, "[isPimpinan] Profil pengguna null untuk userId=${auth.currentUser?.uid}")
                return false
            }
            Log.d(TAG, "[isPimpinan] Profil yang didapat: $profile")
            val roleFromProfile = profile["role"] as? String
            Log.d(TAG, "[isPimpinan] Role dari profil: '$roleFromProfile'")
            val isPimpinan = roleFromProfile == UserRole.PIMPINAN
            Log.d(TAG, "[isPimpinan] Hasil pengecekan: $isPimpinan")
            return isPimpinan
        } catch (e: Exception) {
            Log.e(TAG, "[isPimpinan] Gagal memeriksa: ${e.message}", e)
            return false
        }
    }

    internal suspend fun getUserProfile(forceRefresh: Boolean = false): Map<String, Any>? {
        val userId = auth.currentUser?.uid ?: throw IllegalStateException("Pengguna tidak terautentikasi")
        return getUserProfileById(userId, forceRefresh)
    }
    // KODE BARU (Direvisi)
    suspend fun getUserProfileById(userId: String, forceRefresh: Boolean = false): Map<String, Any>? {
        try {
            if (forceRefresh && userId == auth.currentUser?.uid) {
                auth.currentUser?.reload()?.await()
            }
            val snapshot = database.child("users").child(userId).get().await()
            if (!snapshot.exists()) {
                Log.w("DatabaseHelper", "Profil untuk userId=$userId tidak ditemukan.")
                return null
            }
            return (snapshot.value as? Map<String, Any>)?.plus("userId" to userId)
        } catch (e: CancellationException) {
            // Memastikan pembatalan coroutine diteruskan (SAFE CANCELLATION)
            throw e
        } catch (e: Exception) {
            Log.e("DatabaseHelper", "Gagal mengambil profil untuk userId=$userId", e)
            throw Exception("Gagal mengambil profil pengguna: ${e.message}")
        }
    }
    suspend fun submitReport(reportedUserId: String, reason: String, reportType: String = "USER_REPORT") {
        val reporterId = auth.currentUser?.uid ?: throw IllegalStateException("Pengguna pelapor tidak terautentikasi")
        require(reason.isNotBlank()) { "Alasan laporan tidak boleh kosong" }
        require(reportedUserId.isNotBlank()) { "ID pengguna yang dilaporkan tidak boleh kosong" }

        val reportId = database.child("reports").push().key ?: throw IllegalStateException("Gagal membuat ID laporan")

        val reportData = mapOf(
            "reportId" to reportId,
            "reporterId" to reporterId,
            "reportedUserId" to reportedUserId,
            "reason" to reason,
            "reportType" to reportType,
            "status" to "Pending",
            "timestamp" to System.currentTimeMillis()
        )

        database.child("reports").child(reportId).setValue(reportData).await()
    }
    suspend fun getAllReports(): List<Map<String, Any>> {
        if (!isSupervisor() && !isAdmin()) {
            throw IllegalStateException("Hanya supervisor atau admin yang dapat melihat laporan")
        }
        val snapshot = database.child("reports").get().await()
        if (!snapshot.exists()) return emptyList()

        val reports = snapshot.children.mapNotNull { it.value as? Map<String, Any> }

        val userIds = reports.flatMap { listOf(it["reporterId"] as? String, it["reportedUserId"] as? String) }.filterNotNull().distinct()
        val userProfiles = mutableMapOf<String, Map<String, Any>>()
        userIds.forEach { userId ->
            getUserProfileById(userId)?.let { userProfiles[userId] = it }
        }

        // REVISI: Menggunakan MutableMap untuk penggabungan data yang aman
        return reports.map { report ->
            val newReportMap = report.toMutableMap()
            newReportMap["reporterProfile"] = userProfiles[report["reporterId"] as? String] ?: emptyMap<String, Any>()
            newReportMap["reportedUserProfile"] = userProfiles[report["reportedUserId"] as? String] ?: emptyMap<String, Any>()
            newReportMap.toMap()
        }
    }
    suspend fun blockUser(userIdToBlock: String, isBlocked: Boolean) {
        if (!isSupervisor() && !isAdmin()) {
            throw IllegalStateException("Hanya supervisor atau admin yang dapat memblokir akun")
        }
        if (userIdToBlock == auth.currentUser?.uid) {
            throw IllegalStateException("Tidak dapat memblokir akun sendiri")
        }
        database.child("users").child(userIdToBlock).child("isBlocked").setValue(isBlocked).await()
    }

    suspend fun isUserBlocked(userId: String): Boolean {
        val snapshot = database.child("users").child(userId).child("isBlocked").get().await()
        return snapshot.getValue(Boolean::class.java) ?: false
    }

    suspend fun createAdminAccount(email: String, password: String, username: String) {
        Log.d("DatabaseHelper", "Mencoba membuat akun admin: email=$email")
        require(username.isNotBlank()) { "Username tidak boleh kosong" }
        require(email.isNotBlank()) { "Email tidak boleh kosong" }
        require(password.length >= 6) { "Kata sandi harus minimal 6 karakter" }
        val users = getAllUsers(UserRole.ADMIN)
        if (users.isNotEmpty()) {
            Log.w("DatabaseHelper", "Akun admin sudah ada: $users")
            throw IllegalStateException("Akun admin sudah ada")
        }
        try {
            val authResult = auth.createUserWithEmailAndPassword(email, password).await()
            val user = authResult.user ?: throw IllegalStateException("Gagal membuat pengguna admin")
            val userId = user.uid
            Log.d("DatabaseHelper", "Autentikasi admin berhasil dibuat: userId=$userId")
            user.sendEmailVerification().await()
            Log.d("DatabaseHelper", "Email verifikasi dikirim untuk admin: userId=$userId")
            saveUserProfile(userId, username, email, UserRole.ADMIN, UserLevel.ADMIN)
            auth.currentUser?.reload()?.await()
            Log.d("DatabaseHelper", "Profil admin berhasil disimpan dan disinkronkan")
        } catch (e: FirebaseAuthException) {
            Log.e("DatabaseHelper", "Kesalahan autentikasi saat membuat admin: ${e.errorCode}, ${e.message}", e)
            throw when (e.errorCode) {
                "ERROR_EMAIL_ALREADY_IN_USE" -> IllegalStateException("Email sudah digunakan")
                "ERROR_INVALID_EMAIL" -> IllegalArgumentException("Format email tidak valid")
                "ERROR_WEAK_PASSWORD" -> IllegalArgumentException("Kata sandi harus minimal 6 karakter")
                else -> Exception("Gagal membuat admin: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e("DatabaseHelper", "Kesalahan tak terduga saat membuat admin: ${e.message}", e)
            throw Exception("Gagal membuat admin: ${e.message}")
        }
    }

    suspend fun saveUserProfile(userId: String, username: String, email: String) {
        saveUserProfile(userId, username, email, UserRole.CUSTOMER, UserLevel.USER, emptyList())
    }

    suspend fun saveUserProfile(
        userId: String,
        username: String,
        email: String,
        role: String,
        level: String,
        hobbies: List<Map<String, String>> = emptyList()
    ) {
        Log.d("DatabaseHelper", "Mencoba menyimpan profil pengguna: userId=$userId, role=$role, email=$email, hobbies=$hobbies")
        require(username.isNotBlank()) { "Username tidak boleh kosong" }
        require(email.isNotBlank()) { "Email tidak boleh kosong" }
        require(
            role in listOf(
                UserRole.ADMIN,
                UserRole.PENGELOLA,
                UserRole.SUPERVISOR,
                UserRole.CUSTOMER,
                UserRole.PIMPINAN
            )
        ) { "Role tidak valid: $role" }
        require(
            level in listOf(
                UserLevel.ADMIN,
                UserLevel.PENGELOLA,
                UserLevel.SUPERVISOR,
                UserLevel.USER,
                UserLevel.PIMPINAN
            )
        ) { "Level tidak valid: $level" }
        hobbies.forEachIndexed { index, hobby ->
            require(hobby is Map<*, *>) { "Hobi $index harus Map, bukan ${hobby.javaClass}" }
            require(hobby.containsKey("imageUrl")) { "Hobi $index harus memiliki imageUrl" }
            require(hobby.containsKey("title")) { "Hobi $index harus memiliki title" }
            require(hobby.containsKey("description")) { "Hobi $index harus memiliki description" }
        }
        val isAdmin = isAdmin()
        if (role != UserRole.CUSTOMER && !isAdmin) {
            Log.w("DatabaseHelper", "Non-admin mencoba menetapkan role=$role")
            throw IllegalStateException("Hanya admin yang dapat menetapkan role non-customer")
        }
        val createdAt = System.currentTimeMillis()
        val user = mapOf(
            "username" to username,
            "email" to email,
            "address" to "",
            "profilePhotoUrl" to "",
            "createdAt" to createdAt,
            "role" to role,
            "level" to level,
            "userId" to userId,
            "hobbies" to hobbies
        )
        try {
            database.child("users").child(userId).setValue(user).await()
            Log.d("DatabaseHelper", "Profil pengguna berhasil disimpan: userId=$userId")
            if (userId == auth.currentUser?.uid) {
                auth.currentUser?.reload()?.await()
            }
        } catch (e: Exception) {
            Log.e("DatabaseHelper", "Gagal menyimpan profil pengguna: ${e.message}", e)
            throw Exception("Gagal menyimpan profil pengguna: ${e.message}")
        }
    }

    suspend fun ensureUserProfile() {
        val userId = auth.currentUser?.uid ?: throw IllegalStateException("Pengguna tidak terautentikasi")
        val profile = getUserProfile()
        if (profile == null) {
            val email = auth.currentUser?.email ?: ""
            saveUserProfile(userId, email.substringBefore("@"), email, UserRole.CUSTOMER, UserLevel.USER)
        }
    }

    suspend fun updateUserProfile(
        username: String,
        address: String,
        profilePhotoUrl: String,
        role: String = UserRole.CUSTOMER,
        level: String = UserLevel.USER,
        hobbies: List<Map<String, String>> = emptyList()
    ) {
        Log.d("DatabaseHelper", "Mencoba memperbarui profil pengguna: role=$role, photoUrl=$profilePhotoUrl, hobbies=$hobbies")
        require(username.isNotBlank()) { "Username tidak boleh kosong" }
        require(
            role in listOf(
                UserRole.ADMIN,
                UserRole.PENGELOLA,
                UserRole.SUPERVISOR,
                UserRole.CUSTOMER,
                UserRole.PIMPINAN
            )
        ) { "Role tidak valid: $role" }
        require(
            level in listOf(
                UserLevel.ADMIN,
                UserLevel.PENGELOLA,
                UserLevel.SUPERVISOR,
                UserLevel.USER,

                UserLevel.PIMPINAN
            )
        ) { "Level tidak valid: $level" }
        hobbies.forEachIndexed { index, hobby ->
            require(hobby is Map<*, *>) { "Hobi $index harus Map, bukan ${hobby.javaClass}" }
            require(hobby.containsKey("imageUrl")) { "Hobi $index harus memiliki imageUrl" }
            require(hobby.containsKey("title")) { "Hobi $index harus memiliki title" }
            require(hobby.containsKey("description")) { "Hobi $index harus memiliki description" }
        }
        if (role != UserRole.CUSTOMER || level != UserLevel.USER) {
            if (!isAdmin()) {
                Log.w("DatabaseHelper", "Non-admin mencoba memperbarui role=$role, level=$level")
                throw IllegalStateException("Hanya admin yang dapat memperbarui role atau level")
            }
        }
        val userId = auth.currentUser?.uid ?: throw IllegalStateException("Pengguna tidak terautentikasi")
        val existingProfile = getUserProfile(true)
        val user = mapOf(
            "username" to username,
            "email" to (auth.currentUser?.email ?: ""),
            "address" to address,
            "profilePhotoUrl" to profilePhotoUrl,
            "createdAt" to (existingProfile?.get("createdAt") ?: System.currentTimeMillis()),
            "role" to role,
            "level" to level,
            "userId" to userId,
            "hobbies" to hobbies
        )
        try {
            database.child("users").child(userId).setValue(user).await()
            Log.d("DatabaseHelper", "Profil pengguna berhasil diperbarui: userId=$userId")
            auth.currentUser?.reload()?.await()
        } catch (e: Exception) {
            Log.e("DatabaseHelper", "Gagal memperbarui profil pengguna: ${e.message}", e)
            throw Exception("Gagal memperbarui profil pengguna: ${e.message}")
        }
    }

    suspend fun updateUserProfile(
        userId: String,
        username: String,
        address: String,
        contactPhone: String,
        profilePhotoUrl: String,
        role: String = UserRole.CUSTOMER,
        level: String = UserLevel.USER,
        hobbies: List<Map<String, String>> = emptyList()
    ) {
        Log.d("DatabaseHelper", "Mencoba memperbarui profil pengguna: userId=$userId, username=$username, address=$address, contactPhone=$contactPhone")
        require(username.isNotBlank()) { "Username tidak boleh kosong" }
        require(
            role in listOf(
                UserRole.ADMIN,
                UserRole.PENGELOLA,
                UserRole.SUPERVISOR,
                UserRole.CUSTOMER,
                UserRole.PIMPINAN
            )
        ) { "Role tidak valid: $role" }
        require(
            level in listOf(
                UserLevel.ADMIN,
                UserLevel.PENGELOLA,
                UserLevel.SUPERVISOR,
                UserLevel.USER,
                UserLevel.PIMPINAN
            )
        ) { "Level tidak valid: $level" }
        hobbies.forEachIndexed { index, hobby ->
            require(hobby is Map<*, *>) { "Hobi $index harus Map, bukan ${hobby.javaClass}" }
            require(hobby.containsKey("imageUrl")) { "Hobi $index harus memiliki imageUrl" }
            require(hobby.containsKey("title")) { "Hobi $index harus memiliki title" }
            require(hobby.containsKey("description")) { "Hobi $index harus memiliki description" }
        }
        if (userId != auth.currentUser?.uid && !isAdmin()) {
            Log.w("DatabaseHelper", "Non-admin mencoba memperbarui profil pengguna lain: userId=$userId")
            throw IllegalStateException("Hanya admin yang dapat memperbarui profil pengguna lain")
        }
        val snapshot = database.child("users").child(userId).get().await()
        if (!snapshot.exists()) {
            Log.w("DatabaseHelper", "Profil pengguna tidak ditemukan: userId=$userId")
            throw IllegalStateException("Pengguna tidak ditemukan")
        }
        val existingProfile = snapshot.value as? Map<String, Any> ?: throw IllegalStateException("Data pengguna tidak valid")
        val user = mapOf(
            "username" to username,
            "email" to (existingProfile["email"] ?: ""),
            "address" to address,
            "contactPhone" to contactPhone,
            "profilePhotoUrl" to profilePhotoUrl,
            "createdAt" to (existingProfile["createdAt"] ?: System.currentTimeMillis()),
            "role" to role,
            "level" to level,
            "userId" to userId,
            "hobbies" to hobbies
        )
        try {
            database.child("users").child(userId).setValue(user).await()
            Log.d("DatabaseHelper", "Profil pengguna berhasil diperbarui: userId=$userId")
            if (userId == auth.currentUser?.uid) {
                auth.currentUser?.reload()?.await()
            }
        } catch (e: Exception) {
            Log.e("DatabaseHelper", "Gagal memperbarui profil pengguna: ${e.message}", e)
            throw Exception("Gagal memperbarui profil pengguna: ${e.message}")
        }
    }

    suspend fun getAllUsers(roleFilter: String? = null): List<Map<String, Any>> {
        try {
            val snapshot = if (roleFilter != null) {
                val isAdmin = isAdmin()
                val isSupervisor = isSupervisor()
                val isPimpinan = isPimpinan()
                if (roleFilter == UserRole.CUSTOMER && !(isAdmin || isSupervisor || isPimpinan)) {
                    throw IllegalStateException("Hanya admin atau supervisor dan pimpinan yang dapat mengakses semua pengguna dengan role tertentu")
                }
                database.child("users")
                    .orderByChild("role")
                    .equalTo(roleFilter)
                    .get()
                    .await()
            } else {
                database.child("users").get().await()
            }
            val users = snapshot.children.mapNotNull { child ->
                (child.value as? Map<String, Any>)?.plus("userId" to child.key!!)
            }.filter { roleFilter == null || it["role"] == roleFilter }
            Log.d("DatabaseHelper", "Berhasil mengambil pengguna: filter=$roleFilter, users=$users")
            return users
        } catch (e: Exception) {
            Log.e("DatabaseHelper", "Gagal mengambil pengguna: ${e.message}", e)
            throw Exception("Gagal mengambil pengguna: ${e.message}")
        }
    }

    suspend fun deleteUser(userId: String) {
        Log.d("DatabaseHelper", "Mencoba menghapus pengguna: userId=$userId")
        if (!isAdmin()) {
            Log.w("DatabaseHelper", "Non-admin mencoba menghapus pengguna")
            throw IllegalStateException("Hanya admin yang dapat menghapus pengguna")
        }
        if (userId == auth.currentUser?.uid) {
            Log.w("DatabaseHelper", "Mencoba menghapus akun sendiri")
            throw IllegalStateException("Tidak dapat menghapus akun sendiri")
        }
        try {
            database.child("users").child(userId).removeValue().await()
            Log.d("DatabaseHelper", "Pengguna berhasil dihapus: userId=$userId")
        } catch (e: Exception) {
            Log.e("DatabaseHelper", "Gagal menghapus pengguna: ${e.message}", e)
            throw Exception("Gagal menghapus pengguna: ${e.message}")
        }
    }

    suspend fun updateUserRole(userId: String, role: String, level: String) {
        Log.d("DatabaseHelper", "Mencoba memperbarui role pengguna: userId=$userId, role=$role, level=$level")
        require(
            role in listOf(
                UserRole.ADMIN,
                UserRole.PENGELOLA,
                UserRole.SUPERVISOR,
                UserRole.CUSTOMER,
                UserRole.PIMPINAN
            )
        ) { "Role tidak valid: $role" }
        require(
            level in listOf(
                UserLevel.ADMIN,
                UserLevel.PENGELOLA,
                UserLevel.SUPERVISOR,
                UserLevel.USER,
                UserLevel.PIMPINAN
            )
        ) { "Level tidak valid: $level" }

        val isAuthorized = isAdmin()
        if (!isAuthorized) {
            Log.w("DatabaseHelper", "Non-admin mencoba memperbarui role pengguna")
            throw IllegalStateException("Hanya admin yang dapat memperbarui role pengguna")
        }

        val snapshot = database.child("users").child(userId).get().await()
        if (!snapshot.exists()) {
            Log.w("DatabaseHelper", "Pengguna tidak ditemukan: userId=$userId")
            throw IllegalStateException("Pengguna tidak ditemukan")
        }
        val existingProfile = snapshot.value as? Map<String, Any> ?: throw IllegalStateException("Data pengguna tidak valid")
        val user = mapOf(
            "username" to (existingProfile["username"] ?: ""),
            "email" to (existingProfile["email"] ?: ""),
            "address" to (existingProfile["address"] ?: ""),
            "profilePhotoUrl" to (existingProfile["profilePhotoUrl"] ?: ""),
            "createdAt" to (existingProfile["createdAt"] ?: System.currentTimeMillis()),
            "role" to role,
            "level" to level,
            "userId" to userId,
            "hobbies" to (existingProfile["hobbies"] ?: emptyList<Map<String, String>>())
        )
        try {
            database.child("users").child(userId).setValue(user).await()
            Log.d("DatabaseHelper", "Role pengguna berhasil diperbarui: userId=$userId")
            if (userId == auth.currentUser?.uid) {
                auth.currentUser?.reload()?.await()
            }
        } catch (e: Exception) {
            Log.e("DatabaseHelper", "Gagal memperbarui role pengguna: ${e.message}", e)
            throw Exception("Gagal memperbarui role pengguna: ${e.message}")
        }
    }

    suspend fun getAllProducts(
        category: String? = null,
        minPrice: Double? = null,
        maxPrice: Double? = null,
        searchQuery: String? = null
    ): List<Map<String, Any>> {
        try {
            val snapshot = database.child("products").get().await()
            var products = snapshot.children.mapNotNull { child ->
                (child.value as? Map<String, Any>)?.plus("productId" to child.key!!)
            }
            if (category != null) products = products.filter { it["category"] == category }
            if (minPrice != null) products = products.filter { (it["price"] as? Number)?.toDouble() ?: 0.0 >= minPrice }
            if (maxPrice != null) products = products.filter { (it["price"] as? Number)?.toDouble() ?: Double.MAX_VALUE <= maxPrice }
            if (searchQuery != null) {
                products = products.filter {
                    val name = it["name"] as? String ?: ""
                    name.contains(searchQuery, ignoreCase = true)
                }
            }
            return products
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("DatabaseHelper", "Gagal mengambil produk: ${e.message}", e)
            throw Exception("Gagal mengambil produk: ${e.message}")
        }
    }
    suspend fun getProductById(productId: String): Map<String, Any>? {
        try {
            val snapshot = database.child("products").child(productId).get().await()
            return if (snapshot.exists()) {
                (snapshot.value as? Map<String, Any>)?.plus("productId" to productId)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("DatabaseHelper", "Gagal mengambil produk by ID: ${e.message}", e)
            throw Exception("Gagal mengambil produk: ${e.message}")
        }
    }

    suspend fun getProductsByCreator(userId: String): List<Map<String, Any>> {
        try {
            val snapshot = database.child("products").orderByChild("createdBy").equalTo(userId).get().await()
            val products = snapshot.children.mapNotNull { child ->
                (child.value as? Map<String, Any>)?.plus("productId" to child.key!!)
            }
            Log.d("DatabaseHelper", "Berhasil mengambil produk untuk userId=$userId, count=${products.size}")
            return products
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("DatabaseHelper", "Gagal mengambil produk untuk userId=$userId: ${e.message}", e)
            throw Exception("Gagal mengambil produk: ${e.message}")
        }
    }


    suspend fun addProduct(
        name: String,
        price: Double,
        description: String,
        imageUri: Uri?,
        category: String,
        stock: Int,
        weight: Double // New parameter for weight in kilograms
    ): String {
        try {
            if (!isAdmin() && !isPengelola()) {
                throw IllegalStateException("Hanya admin dan pengelola yang dapat menambah produk")
            }
            require(name.isNotBlank()) { "Nama produk tidak boleh kosong" }
            require(price >= 0) { "Harga produk tidak boleh negatif" }
            require(weight >= 0) { "Berat produk tidak boleh negatif" }
            val userId = auth.currentUser?.uid ?: throw IllegalStateException("Pengguna tidak terautentikasi")
            val productId = database.child("products").push().key
                ?: throw IllegalStateException("Gagal menghasilkan ID produk")
            val imageUrl = if (imageUri != null) uploadProductImage(productId, imageUri) else ""
            val product = mapOf(
                "name" to name,
                "price" to price,
                "description" to description,
                "imageUrl" to imageUrl,
                "category" to category,
                "stock" to stock,
                "weight" to weight, // Add weight to product data
                "createdAt" to System.currentTimeMillis(),
                "createdBy" to userId
            )
            database.child("products").child(productId).setValue(product).await()
            Log.d("DatabaseHelper", "Produk berhasil ditambahkan: productId=$productId, createdBy=$userId")
            return productId
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("DatabaseHelper", "Gagal menambah produk: ${e.message}", e)
            throw Exception("Gagal menambah produk: ${e.message}")
        }
    }

    suspend fun clearCart(userId: String) {
        try {
            database.child("cart").child(userId).removeValue().await()
            Log.d("DatabaseHelper", "Keranjang berhasil dikosongkan: userId=$userId")
        } catch (e: Exception) {
            Log.e("DatabaseHelper", "Gagal mengosongkan keranjang: ${e.message}", e)
            throw Exception("Gagal mengosongkan keranjang: ${e.message}")
        }
    }

    suspend fun updateProduct(
        productId: String,
        name: String,
        price: Double,
        description: String,
        imageUri: Uri?,
        category: String,
        stock: Int,
        weight: Double // New parameter for weight in kilograms
    ) {
        if (!isAdmin() && !isPengelola()) {
            Log.w("DatabaseHelper", "Non-admin atau non-pengelola mencoba memperbarui produk")
            throw IllegalStateException("Hanya admin dan pengelola yang dapat memperbarui produk")
        }
        require(name.isNotBlank()) { "Nama produk tidak boleh kosong" }
        require(price >= 0) { "Harga produk tidak boleh negatif" }
        require(description.isNotBlank()) { "Deskripsi produk tidak boleh kosong" }
        require(category.isNotBlank()) { "Kategori produk tidak boleh kosong" }
        require(stock >= 0) { "Stok produk tidak boleh negatif" }
        require(weight >= 0) { "Berat produk tidak boleh negatif" }
        val existingProduct = database.child("products").child(productId).get().await()
        if (!existingProduct.exists()) {
            throw IllegalStateException("Produk tidak ditemukan: id=$productId")
        }
        val existingData = existingProduct.value as? Map<String, Any> ?: mapOf()
        val userId = auth.currentUser?.uid ?: throw IllegalStateException("Pengguna tidak terautentikasi")
        if (existingData["createdBy"] != userId && !isAdmin()) {
            throw IllegalStateException("Hanya pembuat produk atau admin yang dapat memperbarui produk ini")
        }
        val imageUrl = if (imageUri != null) {
            uploadProductImage(productId, imageUri)
        } else {
            existingData["imageUrl"] as? String ?: ""
        }
        val product = mapOf(
            "name" to name,
            "price" to price,
            "description" to description,
            "imageUrl" to imageUrl,
            "category" to category,
            "stock" to stock,
            "weight" to weight, // Add weight to product data
            "createdAt" to (existingData["createdAt"] ?: System.currentTimeMillis()),
            "createdBy" to (existingData["createdBy"] ?: userId)
        )
        try {
            database.child("products").child(productId).setValue(product).await()
            Log.d("DatabaseHelper", "Produk berhasil diperbarui: id=$productId, name=$name")
        } catch (e: Exception) {
            Log.e("DatabaseHelper", "Gagal memperbarui produk: ${e.message}", e)
            throw Exception("Gagal memperbarui produk: ${e.message}")
        }
    }

    suspend fun updateProductStock(productId: String, quantity: Int) {
        require(quantity >= 0) { "Jumlah stok tidak boleh negatif" }
        val existingProduct = database.child("products").child(productId).get().await()
        if (!existingProduct.exists()) {
            throw IllegalStateException("Produk tidak ditemukan: id=$productId")
        }
        val existingData = existingProduct.value as? Map<String, Any> ?: mapOf()
        val currentStock = (existingData["stock"] as? Number)?.toInt() ?: 0
        if (quantity > currentStock) {
            throw IllegalStateException("Stok tidak cukup untuk produk: id=$productId, tersedia: $currentStock, diminta: $quantity")
        }
        val updatedStock = currentStock - quantity
        val updates = mapOf(
            "stock" to updatedStock
        )
        try {
            database.child("products").child(productId).updateChildren(updates).await()
            Log.d("DatabaseHelper", "Stok produk berhasil diperbarui: id=$productId, stock=$updatedStock")
        } catch (e: Exception) {
            Log.e("DatabaseHelper", "Gagal memperbarui stok produk: ${e.message}", e)
            throw Exception("Gagal memperbarui stok produk: ${e.message}")
        }
    }

    suspend fun deleteProduct(productId: String) {
        if (!isAdmin() && !isPengelola()) {
            Log.w("DatabaseHelper", "Non-admin atau non-pengelolah mencoba menghapus produk")
            throw IllegalStateException("Hanya admin dan pengelola yang dapat menghapus produk")
        }
        val existingProduct = database.child("products").child(productId).get().await()
        if (!existingProduct.exists()) {
            throw IllegalStateException("Produk tidak ditemukan: id=$productId")
        }
        val existingData = existingProduct.value as? Map<String, Any> ?: mapOf()
        val userId = auth.currentUser?.uid ?: throw IllegalStateException("Pengguna tidak terautentikasi")
        if (existingData["createdBy"] != userId && !isAdmin()) {
            throw IllegalStateException("Hanya pembuat produk atau admin yang dapat menghapus produk ini")
        }
        try {
            database.child("products").child(productId).removeValue().await()
            Log.d("DatabaseHelper", "Produk berhasil dihapus: id=$productId")
        } catch (e: Exception) {
            Log.e("DatabaseHelper", "Gagal menghapus produk: ${e.message}", e)
            throw Exception("Gagal menghapus produk: ${e.message}")
        }
    }

    suspend fun addProductRating(productId: String, rating: Double, review: String) {
        val userId = auth.currentUser?.uid ?: throw IllegalStateException("Pengguna tidak resmi")
        require(rating in 0.0..5.0) { "Rating harus antara 0 dan 5" }
        require(review.isNotBlank()) { "Ulasan tidak boleh kosong" }
        val ratingData = mapOf(
            "rating" to rating,
            "review" to review,
            "timestamp" to System.currentTimeMillis()
        )
        try {
            database.child("products").child(productId).child("ratings").child(userId)
                .setValue(ratingData).await()
            Log.d("DatabaseHelper", "Rating produk berhasil ditambahkan: productId=$productId, userId=$userId")
        } catch (e: Exception) {
            Log.e("DatabaseHelper", "Gagal menambah rating produk: ${e.message}", e)
            throw Exception("Gagal menambah rating produk: ${e.message}")
        }
    }

    suspend fun addToWishlist(productId: String) {
        val userId = auth.currentUser?.uid ?: throw IllegalStateException("Pengguna tidak resmi")
        try {
            database.child("wishlist").child(userId).child(productId).setValue(true).await()
            Log.d("DatabaseHelper", "Produk berhasil ditambah ke wishlist: userId=$userId, productId=$productId")
        } catch (e: Exception) {
            Log.e("DatabaseHelper", "Gagal menambah produk ke wishlist: ${e.message}", e)
            throw Exception("Gagal menambah produk ke wishlist: ${e.message}")
        }
    }

    suspend fun removeFromWishlist(productId: String) {
        val userId = auth.currentUser?.uid ?: throw IllegalStateException("Pengguna tidak resmi")
        try {
            database.child("wishlist").child(userId).child(productId).removeValue().await()
            Log.d("DatabaseHelper", "Produk berhasil dihapus dari wishlist: userId=$userId, productId=$productId")
        } catch (e: Exception) {
            Log.e("DatabaseHelper", "Gagal menghapus produk dari wishlist: ${e.message}", e)
            throw Exception("Gagal menghapus produk dari wishlist: ${e.message}")
        }
    }

    suspend fun getWishlist(): List<Map<String, Any>> {
        val userId = auth.currentUser?.uid ?: throw IllegalStateException("Pengguna tidak resmi")
        try {
            val snapshot = database.child("wishlist").child(userId).get().await()
            val productIds = snapshot.children.map { it.key!! }
            val products = getAllProducts().filter { it["productId"] in productIds }
            Log.d("DatabaseHelper", "Berhasil mengambil wishlist: userId=$userId, count=${products.size}")
            return products
        } catch (e: Exception) {
            Log.e("DatabaseHelper", "Gagal mengambil wishlist: ${e.message}", e)
            throw Exception("Gagal mengambil wishlist: ${e.message}")
        }
    }

    suspend fun addToCart(productId: String, quantity: Int) {
        val userId = auth.currentUser?.uid ?: throw IllegalStateException("Pengguna tidak resmi")
        require(quantity > 0) { "Jumlah produk harus lebih dari 0" }
        val cartItem = mapOf(
            "quantity" to quantity,
            "addedAt" to System.currentTimeMillis()
        )
        try {
            database.child("cart").child(userId).child(productId).setValue(cartItem).await()
            Log.d("DatabaseHelper", "Produk berhasil ditambahkan ke keranjang: userId=$userId, productId=$productId")
        } catch (e: Exception) {
            Log.e("DatabaseHelper", "Gagal menambah produk ke keranjang: ${e.message}", e)
            throw Exception("Gagal menambah produk ke keranjang: ${e.message}")
        }
    }

    suspend fun updateCartItem(productId: String, quantity: Int) {
        val userId = auth.currentUser?.uid ?: throw IllegalStateException("Pengguna tidak resmi")
        require(quantity >= 0) { "Jumlah produk tidak boleh negatif" }
        try {
            if (quantity == 0) {
                database.child("cart").child(userId).child(productId).removeValue().await()
                Log.d("DatabaseHelper", "Produk dihapus dari keranjang: userId=$userId, productId=$productId")
            } else {
                val cartItem = mapOf(
                    "quantity" to quantity,
                    "addedAt" to System.currentTimeMillis()
                )
                database.child("cart").child(userId).child(productId).setValue(cartItem).await()
                Log.d("DatabaseHelper", "Jumlah produk di keranjang diperbarui: userId=$userId, productId=$productId, quantity=$quantity")
            }
        } catch (e: Exception) {
            Log.e("DatabaseHelper", "Gagal memperbaharui keranjang: ${e.message}", e)
            throw Exception("Gagal memperbaharui keranjang: ${e.message}")
        }
    }

    suspend fun getCart(): List<Map<String, Any>> {
        val userId = auth.currentUser?.uid ?: throw IllegalStateException("Pengguna tidak resmi")
        try {
            val snapshot = database.child("cart").child(userId).get().await()
            val cartItems = snapshot.children.mapNotNull { child ->
                (child.value as? Map<String, Any>)?.plus("productId" to child.key!!)
            }
            Log.d("DatabaseHelper", "Berhasil mengambil keranjang: userId=$userId, count=${cartItems.size}")
            return cartItems
        } catch (e: Exception) {
            Log.e("DatabaseHelper", "Gagal mengambil keranjang: ${e.message}", e)
            throw Exception("Gagal mengambil keranjang: ${e.message}")
        }
    }

    suspend fun createOrder(
        items: Map<String, Map<String, Any>>,
        totalPrice: Double,
        shippingAddress: String,
        paymentMethod: String,
        shippingService: String = ""
    ): String {
        val userId = auth.currentUser?.uid ?: throw IllegalStateException("Pengguna tidak resmi")
        require(totalPrice >= 0) { "Tanggal harga tidak boleh negatif" }
        require(shippingAddress.isNotBlank()) { "Alamat penggiran tidak boleh kosong" }
        require(paymentMethod.isNotBlank()) { "Metode pembayaran tidak boleh kosong" }
        require(shippingService.isNotBlank()) { "Jasa penggiran tidak boleh kosong" }
        require(items.isNotEmpty()) { "Pesanan harus berisi setidaknya satu item" }
        val orderId = database.child("orders").push().key
            ?: throw IllegalStateException("Gagal menghasilkan ID pesanan")
        val order = mapOf(
            "userId" to userId,
            "status" to "PENDING",
            "totalPrice" to totalPrice,
            "createdAt" to System.currentTimeMillis(),
            "shippingAddress" to shippingAddress,
            "paymentMethod" to paymentMethod,
            "shippingService" to shippingService,
            "items" to items
        )
        try {
            database.child("orders").child(orderId).setValue(order).await()
            database.child("cart").child(userId).removeValue().await()
            Log.d("DatabaseHelper", "Pesanan berhasil dibuat: orderId=$orderId, userId=$userId")
            return orderId
        } catch (e: Exception) {
            Log.e("DatabaseHelper", "Gagal membuat pesanan: ${e.message}", e)
            throw Exception("Gagal membuat pesanan: ${e.message}")
        }
    }

    suspend fun getOrders(): List<Map<String, Any>> {
        try {
            val auth = FirebaseAuth.getInstance()
            if (auth.currentUser == null) {
                throw Exception("Pengguna tidak resmi")
            }
            if (!auth.currentUser?.isEmailVerified!!) {
                throw Exception("Email belum diverifikasi")
            }
            ensureUserProfile()
            val query = database.child("orders").orderByChild("userId").equalTo(auth.currentUser?.uid)
            val snapshot = query.get().await()
            if (!snapshot.exists()) {
                Log.d("DatabaseHelper", "Tidak ada pesanan ditemukan untuk userId=${auth.currentUser?.uid}")
                return emptyList()
            }
            return snapshot.children.mapNotNull { child ->
                (child.value as? Map<String, Any>)?.plus("orderId" to child.key!!)
            }
        } catch (e: Exception) {
            Log.e("DatabaseHelper", "Gagal mengambil pesanan: ${e.message}, userId=${auth.currentUser?.uid}, emailVerified=${auth.currentUser?.isEmailVerified}", e)
            throw Exception("Gagal mengambil pesanan: ${e.message}")
        }
    }

    suspend fun getAllOrders(): List<Map<String, Any>> {
        try {
            val auth = FirebaseAuth.getInstance()
            if (auth.currentUser == null) {
                throw Exception("Pengguna tidak resmi")
            }
            if (!auth.currentUser?.isEmailVerified!!) {
                throw Exception("Email belum diverifikasi")
            }
            val isAdmin = isAdmin()
            val isPengelola = isPengelola()
            val isSupervisor = isSupervisor()
            val isPimpinan = isPimpinan()

            // REVISI: Pengelola juga bisa mengakses semua order
            if (!isAdmin && !isSupervisor && !isPimpinan && !isPengelola) {
                throw IllegalStateException("Hanya admin, pengelola, supervisor, atau pimpinan yang dapat mengakses semua pesanan")
            }
            ensureUserProfile()
            val snapshot = database.child("orders").get().await()
            if (!snapshot.exists()) {
                Log.d("DatabaseHelper", "Tidak ada pesanan yang ditemukan")
                return emptyList()
            }
            val orders = snapshot.children.mapNotNull { child ->
                (child.value as? Map<String, Any>)?.plus("orderId" to child.key!!)
            }
            // Mengambil profil pengguna untuk setiap pesanan agar bisa menampilkan nama customer
            val userIds = orders.mapNotNull { it["userId"] as? String }.distinct()
            val userProfiles = mutableMapOf<String, Map<String, Any>>()
            userIds.forEach { userId ->
                val userSnapshot = database.child("users").child(userId).get().await()
                if(userSnapshot.exists()) {
                    userProfiles[userId] = userSnapshot.value as Map<String, Any>
                }
            }

            return orders.map { order ->
                val userId = order["userId"] as? String
                if (userId != null && userProfiles.containsKey(userId)) {
                    order + mapOf("customerProfile" to userProfiles[userId]!!)
                } else {
                    order
                }
            }
        } catch (e: Exception) {
            Log.e("DatabaseHelper", "Gagal mengambil semua pesanan: ${e.message}", e)
            throw Exception("Gagal mengambil semua pesanan: ${e.message}")
        }
    }
    suspend fun getSalesDataForPeriod(days: Int): Map<String, Map<String, Int>> {
        return try {
            val allOrders = getAllOrders()
            val now = LocalDate.now()
            val startDate = now.minusDays(days.toLong() - 1)
            val daysOfWeek = listOf("Minggu", "Senin", "Selasa", "Rabu", "Kamis", "Jumat", "Sabtu")
            val calendar = Calendar.getInstance()

            // Get product details for mapping product IDs to names
            val products = getAllProducts()
            val productIdToName = products.associate { it["productId"] as String to (it["name"] as? String ?: "Unknown") }

            // Group sales by date and product
            val salesByDateAndProduct = mutableMapOf<String, MutableMap<String, Int>>()

            allOrders.forEach { order ->
                val timestamp = order["createdAt"] as? Long ?: return@forEach
                val date = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
                if (date.isBefore(startDate)) return@forEach

                calendar.time = Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant())
                val dayIndex = calendar.get(Calendar.DAY_OF_WEEK) - 1 // Sunday = 1, adjust to 0-based
                val dayName = daysOfWeek[dayIndex]

                val items = order["items"] as? Map<String, Map<String, Any>> ?: return@forEach
                items.forEach { (productId, item) ->
                    val productName = productIdToName[productId] ?: "Unknown"
                    val quantity = (item["quantity"] as? Number)?.toInt() ?: 0
                    if (quantity > 0) {
                        val productSales = salesByDateAndProduct.getOrPut(dayName) { mutableMapOf() }
                        productSales[productName] = (productSales[productName] ?: 0) + quantity
                    }
                }
            }

            // Ensure all days in the range are included, even with no sales
            val fullDateRange = (0 until days).map {
                val date = now.minusDays(it.toLong())
                calendar.time = Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant())
                daysOfWeek[calendar.get(Calendar.DAY_OF_WEEK) - 1]
            }.reversed() // Oldest to newest

            fullDateRange.associateWith { salesByDateAndProduct[it] ?: emptyMap() }
        } catch (e: Exception) {
            Log.e("DatabaseHelper", "Gagal mengolah data penjualan: ${e.message}", e)
            emptyMap()
        }
    }
    suspend fun getOrdersForPengelola(pengelolaId: String): List<Map<String, Any>> {
        try {
            val myProductsSnapshot = database.child("products").orderByChild("createdBy").equalTo(pengelolaId).get().await()
            val myProductIds = myProductsSnapshot.children.mapNotNull { it.key }.toSet()

            if (myProductIds.isEmpty()) {
                return emptyList()
            }

            val allOrdersSnapshot = database.child("orders").get().await()
            if (!allOrdersSnapshot.exists()) return emptyList()

            val allOrders = allOrdersSnapshot.children.mapNotNull {
                (it.value as? Map<String, Any>)?.plus("orderId" to it.key!!)
            }

            // REVISI LOGIKA FILTER
            val filteredOrders = allOrders.filter { order ->
                val items = order["items"] as? Map<String, Map<String, Any>>
                // Memeriksa apakah KEY dari map items (yang merupakan productId) ada di dalam set produk milik pengelola
                items?.keys?.any { productId -> productId in myProductIds } ?: false
            }

            val userIds = filteredOrders.mapNotNull { it["userId"] as? String }.distinct()
            val userProfiles = mutableMapOf<String, Map<String, Any>>()
            userIds.forEach { userId ->
                database.child("users").child(userId).get().await().let { userSnapshot ->
                    if(userSnapshot.exists()) userProfiles[userId] = userSnapshot.value as Map<String, Any>
                }
            }

            return filteredOrders.map { order ->
                val userId = order["userId"] as? String
                if (userId != null && userProfiles.containsKey(userId)) {
                    order + mapOf("customerProfile" to userProfiles[userId]!!)
                } else {
                    order
                }
            }

        } catch (e: Exception) {
            Log.e("DatabaseHelper", "Gagal mengambil pesanan untuk pengelola $pengelolaId", e)
            throw Exception("Gagal mengambil pesanan untuk pengelola: ${e.message}")
        }
    }
    suspend fun getOrderById(orderId: String): Map<String, Any>? {
        try {
            val snapshot = database.child("orders").child(orderId).get().await()
            if (snapshot.exists()) {
                val order = (snapshot.value as? Map<String, Any>)?.plus("orderId" to orderId)
                // Ambil juga profil customer untuk ditampilkan di detail
                val userId = order?.get("userId") as? String
                if (userId != null) {
                    val userSnapshot = database.child("users").child(userId).get().await()
                    if (userSnapshot.exists()) {
                        return order.plus("customerProfile" to (userSnapshot.value as Map<String, Any>))
                    }
                }
                return order
            } else {
                return null
            }
        } catch (e: Exception) {
            Log.e("DatabaseHelper", "Gagal mengambil pesanan by ID: $orderId", e)
            throw Exception("Gagal mengambil pesanan: ${e.message}")
        }
    }

    /**
     * BARU: Fungsi inti untuk memperbarui status pesanan.
     * Dilengkapi dengan validasi peran untuk keamanan.
     */
    suspend fun updateOrderStatus(orderId: String, newStatus: String) {
        val currentUserId = auth.currentUser?.uid ?: throw IllegalStateException("Pengguna tidak terautentikasi")

        val orderSnapshot = database.child("orders").child(orderId).get().await()
        if (!orderSnapshot.exists()) {
            throw IllegalStateException("Pesanan tidak ditemukan")
        }
        val order = orderSnapshot.value as Map<String, Any>
        val orderOwnerId = order["userId"] as String

        val isPengelola = isPengelola()
        val isAdmin = isAdmin()

        val canUpdate = when (newStatus) {
            // Hanya pengelola & admin yang boleh memproses dan mengirim
            OrderStatus.DIKEMAS, OrderStatus.DIKIRIM -> isPengelola || isAdmin
            // Hanya customer pemilik order yang boleh menandai diterima
            OrderStatus.DITERIMA -> currentUserId == orderOwnerId
            // Hanya customer yang boleh menyelesaikan (setelah rating)
            OrderStatus.SELESAI -> currentUserId == orderOwnerId
            // Pengelola/admin bisa membatalkan, customer juga (jika ada logika tambahan nanti)
            OrderStatus.DIBATALKAN -> isPengelola || isAdmin || currentUserId == orderOwnerId
            else -> false
        }

        if (!canUpdate) {
            val profile = getUserProfile()
            val role = profile?.get("role")
            Log.w("DatabaseHelper", "Izin ditolak untuk update status. UserID: $currentUserId, Role: $role, Target Status: $newStatus")
            throw IllegalStateException("Anda tidak memiliki izin untuk mengubah status pesanan ke '$newStatus'")
        }

        try {
            val updates = mapOf(
                "status" to newStatus,
                "updatedAt" to System.currentTimeMillis()
            )
            database.child("orders").child(orderId).updateChildren(updates).await()
            Log.d("DatabaseHelper", "Status pesanan berhasil diperbarui: orderId=$orderId, status=$newStatus")
        } catch (e: Exception) {
            Log.e("DatabaseHelper", "Gagal memperbarui status pesanan: ${e.message}", e)
            throw Exception("Gagal memperbarui status pesanan: ${e.message}")
        }
    }


    suspend fun addPromotion(
        title: String,
        description: String,
        startDate: Long,
        endDate: Long,
        category: String,
        discountPercentage: Double
    ): String {
        if (!isAdmin()) {
            Log.w("DatabaseHelper", "Non-admin mencoba menambah promosi")
            throw IllegalStateException("Hanya admin yang dapat menambah promosi")
        }
        require(title.isNotBlank()) { "Judul promosi tidak boleh kosong" }
        require(description.isNotBlank()) { "Deskripsi promosi tidak boleh kosong" }
        require(startDate <= endDate) { "Tanggal mulai harus sebelum tanggal berakhir" }
        require(category.isNotBlank()) { "Kategori promosi tidak boleh kosong" }
        require(discountPercentage in 0.0..100.0) { "Persentase diskon harus antara 0 dan 100" }
        val promoId = database.child("promotions").push().key
            ?: throw IllegalStateException("Gagal menghasilkan ID promosi")
        val promotion = mapOf(
            "title" to title,
            "description" to description,
            "startDate" to startDate,
            "endDate" to endDate,
            "category" to category,
            "discountPercentage" to discountPercentage,
            "createdAt" to System.currentTimeMillis()
        )
        try {
            database.child("promotions").child(promoId).setValue(promotion).await()
            Log.d("DatabaseHelper", "Promosi berhasil ditambahkan: promoId=$promoId, title=$title")
            return promoId
        } catch (e: Exception) {
            Log.e("DatabaseHelper", "Gagal menambah promosi: ${e.message}", e)
            throw Exception("Gagal menambah promosi: ${e.message}")
        }
    }

    suspend fun getPromotions(category: String? = null): List<Map<String, Any>> {
        try {
            val snapshot = database.child("promotions").get().await()
            var promotions = snapshot.children.mapNotNull { child ->
                (child.value as? Map<String, Any>)?.plus("promoId" to child.key!!)
            }
            if (category != null) {
                promotions = promotions.filter { it["category"] == category }
            }
            Log.d("DatabaseHelper", "Berhasil mengambil promosi: count=${promotions.size}")
            return promotions
        } catch (e: Exception) {
            Log.e("DatabaseHelper", "Gagal mengambil promosi: ${e.message}", e)
            throw Exception("Gagal mengambil promosi: ${e.message}")
        }
    }

    suspend fun createSupportTicket(subject: String, message: String): String {
        val userId = auth.currentUser?.uid ?: throw IllegalStateException("Pengguna tidak resmi")
        require(subject.isNotBlank()) { "Subjek tiket tidak boleh kosong" }
        require(message.isNotBlank()) { "Pesan tiket tidak boleh kosong" }
        val ticketId = database.child("support").push().key
            ?: throw IllegalStateException("Gagal menghasilkan ID tiket")
        val ticket = mapOf(
            "userId" to userId,
            "subject" to subject,
            "message" to message,
            "status" to "OPEN",
            "createdAt" to System.currentTimeMillis()
        )
        try {
            database.child("support").child(ticketId).setValue(ticket).await()
            Log.d("DatabaseHelper", "Tiket dukungan berhasil dibuat: ticketId=$ticketId, userId=$userId")
            return ticketId
        } catch (e: Exception) {
            Log.e("DatabaseHelper", "Gagal membuat tiket dukungan: ${e.message}", e)
            throw Exception("Gagal membuat tiket dukungan: ${e.message}")
        }
    }

    suspend fun addSupportResponse(ticketId: String, message: String) {
        val userId = auth.currentUser?.uid ?: throw IllegalStateException("Pengguna tidak resmi")
        require(message.isNotBlank()) { "Pesan respons tidak boleh kosong" }
        val responseId = database.child("support").child(ticketId).child("responses").push().key
            ?: throw IllegalStateException("Gagal menghasilkan ID respons")
        val response = mapOf(
            "message" to message,
            "sender" to userId,
            "timestamp" to System.currentTimeMillis()
        )
        try {
            database.child("support").child(ticketId).child("responses").child(responseId)
                .setValue(response).await()
            Log.d("DatabaseHelper", "Respons dukungan berhasil ditambahkan: ticketId=$ticketId, responseId=$responseId")
        } catch (e: Exception) {
            Log.e("DatabaseHelper", "Gagal menambah respons dukungan: ${e.message}", e)
            throw Exception("Gagal menambah respons dukungan: ${e.message}")
        }
    }

    suspend fun getSupportTickets(): List<Map<String, Any>> {
        val userId = auth.currentUser?.uid ?: throw IllegalStateException("Pengguna tidak resmi")
        try {
            val isAdmin = isAdmin()
            val snapshot = if (isAdmin) {
                database.child("support").get().await()
            } else {
                database.child("support").orderByChild("userId").equalTo(userId).get().await()
            }
            val tickets = snapshot.children.mapNotNull { child ->
                (child.value as? Map<String, Any>)?.plus("ticketId" to child.key!!)
            }
            Log.d("DatabaseHelper", "Berhasil mengambil tiket dukungan: userId=$userId, count=${tickets.size}")
            return tickets
        } catch (e: Exception) {
            Log.e("DatabaseHelper", "Gagal mengambil tiket dukungan: ${e.message}", e)
            throw Exception("Gagal mengambil tiket dukungan: ${e.message}")
        }
    }

    suspend fun updateInfo(
        contactEmail: String,
        contactPhone: String,
        returnPolicy: String,
        faq: List<Map<String, String>>
    ) {
        if (!isAdmin()) {
            Log.w("DatabaseHelper", "Non-admin mencoba memperbarui info")
            throw IllegalStateException("Hanya admin yang dapat memperbarui info")
        }
        require(contactEmail.isNotBlank()) { "Email kontak tidak boleh kosong" }
        require(contactPhone.isNotBlank()) { "Nomor telepon kontak tidak boleh kosong" }
        require(returnPolicy.isNotBlank()) { "Kebijakan pengembalian tidak boleh kosong" }
        require(faq.isNotEmpty()) { "FAQ tidak boleh kosong" }
        faq.forEach { item ->
            require(item.containsKey("question") && item.containsKey("answer")) { "Setiap FAQ harus memiliki pertanyaan dan jawaban" }
        }
        val info = mapOf(
            "contact" to mapOf(
                "email" to contactEmail,
                "phone" to contactPhone
            ),
            "returnPolicy" to returnPolicy,
            "faq" to faq
        )
        try {
            database.child("info").setValue(info).await()
            Log.d("DatabaseHelper", "Informasi statis berhasil diperbarui")
        } catch (e: Exception) {
            Log.e("DatabaseHelper", "Gagal memperbarui informasi statis: ${e.message}", e)
            throw Exception("Gagal memperbarui informasi statis: ${e.message}")
        }
    }

    suspend fun getInfo(): Map<String, Any> {
        try {
            val snapshot = database.child("info").get().await()
            val info = snapshot.value as? Map<String, Any> ?: emptyMap()
            Log.d("DatabaseHelper", "Berhasil mengambil informasi statis: $info")
            return info
        } catch (e: Exception) {
            Log.e("DatabaseHelper", "Gagal mengambil informasi statis: ${e.message}", e)
            throw Exception("Gagal mengambil informasi statis: ${e.message}")
        }
    }

    suspend fun saveOrder(
        orderDetails: Map<String, Map<String, Any>>,
        totalPrice: Double,
        shippingAddress: String,
        paymentMethod: String,
        shippingService: String
    ): String {
        val userId = auth.currentUser?.uid ?: throw IllegalStateException("Pengguna tidak resmi")
        return createOrder(orderDetails, totalPrice, shippingAddress, paymentMethod, shippingService)
    }

    suspend fun findFirstPengelolaId(): String? {
        try {
            val snapshot = database.child("public_roles/pengelola").limitToFirst(1).get().await()
            val pengelolaId = snapshot.children.firstOrNull()?.key
            Log.d("DatabaseHelper", "findFirstPengelolaId berhasil menemukan: $pengelolaId")
            return pengelolaId
        } catch (e: CancellationException) {
            Log.d("DatabaseHelper", "findFirstPengelolaId dibatalkan.")
            throw e
        } catch (e: Exception) {
            Log.e("DatabaseHelper", "Gagal menemukan ID Pengelola dari public_roles", e)
            return null
        }
    }

    suspend fun updateOrderRatingAndReview(orderId: String, rating: Double, review: String) {
        val userId = auth.currentUser?.uid ?: throw IllegalStateException("Pengguna tidak resmi")
        require(rating > 0.0 && rating <= 5.0) { "Rating harus antara 1 dan 5" }
        require(review.isNotBlank()) { "Ulasan tidak boleh kosong" }
        try {
            val snapshot = database.child("orders").child(orderId).get().await()
            if (!snapshot.exists()) {
                Log.w("DatabaseHelper", "Pesanan tidak ditemukan: orderId=$orderId")
                throw IllegalStateException("Pesanan tidak ditemukan")
            }
            val existingOrder = snapshot.value as? Map<String, Any>
                ?: throw IllegalStateException("Data pesanan tidak valid")
            if (existingOrder["userId"] != userId) {
                Log.w("DatabaseHelper", "Pengguna tidak berhak memperbarui pesanan: orderId=$orderId, userId=$userId")
                throw IllegalStateException("Hanya pemilik pesanan yang dapat memberikan penilaian")
            }
            val updates = mapOf(
                "rating" to rating,
                "review" to review,
                "updatedAt" to System.currentTimeMillis()
            )
            database.child("orders").child(orderId).updateChildren(updates).await()
            Log.d("DatabaseHelper", "Penilaian dan ulasan pesanan berhasil diperbarui: orderId=$orderId, rating=$rating")
        } catch (e: Exception) {
            Log.e("DatabaseHelper", "Gagal memperbarui penilaian pesanan: ${e.message}", e)
            throw Exception("Gagal memperbarui penilaian pesanan: ${e.message}")
        }
    }

    suspend fun submitComplaint(complaint: Map<String, Any>) {
        val complaintId = Firebase.database.reference.child("complaints").push().key
            ?: throw IllegalStateException("Failed to generate complaint ID")
        Firebase.database.reference
            .child("complaints")
            .child(complaintId)
            .setValue(complaint)
            .await()
    }

    suspend fun getAllComplaints(): List<Map<String, Any>> {
        try {
            val auth = FirebaseAuth.getInstance()
            if (auth.currentUser == null) {
                throw Exception("Pengguna tidak terautentikasi")
            }
            if (!auth.currentUser?.isEmailVerified!!) {
                throw Exception("Email belum diverifikasi")
            }
            val isAdmin = isAdmin()
            val isSupervisor = isSupervisor()
            if (!isAdmin && !isSupervisor) {
                throw IllegalStateException("Hanya admin atau supervisor yang dapat mengakses semua keluhan")
            }
            ensureUserProfile()
            val snapshot = database.child("complaints").get().await()
            if (!snapshot.exists()) {
                Log.d("DatabaseHelper", "Tidak ada keluhan ditemukan")
                return emptyList()
            }
            val complaints = snapshot.children.mapNotNull { child ->
                (child.value as? Map<String, Any>)?.plus("complaintId" to child.key!!)
            }
            Log.d("DatabaseHelper", "Berhasil mengambil keluhan: count=${complaints.size}")
            return complaints
        } catch (e: Exception) {
            Log.e("DatabaseHelper", "Gagal mengambil keluhan: ${e.message}", e)
            throw Exception("Gagal mengambil keluhan: ${e.message}")
        }
    }

    suspend fun updateComplaint(complaintId: String, updatedComplaint: Map<String, Any>) {
        val userId = auth.currentUser?.uid ?: throw IllegalStateException("Pengguna tidak terautentikasi")
        try {
            val isSupervisor = isSupervisor()
            if (!isSupervisor) {
                Log.w("DatabaseHelper", "Non-supervisor mencoba memperbarui keluhan: userId=$userId")
                throw IllegalStateException("Hanya supervisor yang dapat memperbarui keluhan")
            }

            val snapshot = database.child("complaints").child(complaintId).get().await()
            if (!snapshot.exists()) {
                Log.w("DatabaseHelper", "Keluhan tidak ditemukan: complaintId=$complaintId")
                throw IllegalStateException("Keluhan tidak ditemukan")
            }

            val existingComplaint = snapshot.value as? Map<String, Any> ?: throw IllegalStateException("Data keluhan tidak valid")
            val updatedData = existingComplaint.toMutableMap().apply {
                updatedComplaint.forEach { (key, value) ->
                    when (key) {
                        "status" -> {
                            val newStatus = value as? String
                            if (newStatus !in listOf("Pending", "Resolved")) {
                                throw IllegalArgumentException("Status keluhan hanya boleh 'Pending' atau 'Resolved'")
                            }
                            this[key] = newStatus as Any
                        }
                        "text", "userId", "createdAt" -> {
                            Log.w("DatabaseHelper", "Bidang '$key' tidak dapat diperbarui oleh supervisor")
                        }
                        else -> this[key] = value
                    }
                }
                this["updatedAt"] = System.currentTimeMillis()
                this["updatedBy"] = userId
            }

            database.child("complaints").child(complaintId).setValue(updatedData).await()
            Log.d("DatabaseHelper", "Keluhan berhasil diperbarui: complaintId=$complaintId, status=${updatedData["status"]}")
        } catch (e: Exception) {
            Log.e("DatabaseHelper", "Gagal memperbarui keluhan: ${e.message}", e)
            throw Exception("Gagal memperbarui keluhan: ${e.message}")
        }
    }

}
