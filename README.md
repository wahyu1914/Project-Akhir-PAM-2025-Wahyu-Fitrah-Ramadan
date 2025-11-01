# Proyek E-Commerce Android

Sebuah aplikasi e-commerce seluler native Android yang dibangun menggunakan **Kotlin** dan **Jetpack Compose**. Aplikasi ini dirancang dengan arsitektur multi-peran yang kompleks untuk melayani berbagai jenis pengguna.

Seluruh backend aplikasi ini didukung penuh oleh **Firebase**, memanfaatkan:
* **Firebase Realtime Database** untuk persistensi semua data (produk, pengguna, pesanan, dll.).
* **Firebase Authentication** untuk manajemen login dan registrasi pengguna.

## ☁️ Manajemen Gambar dengan Cloudinary

Aplikasi ini **tidak** menggunakan Firebase Storage untuk gambar. Sebaliknya, aplikasi ini mengadopsi alur kerja yang efisien:

1.  Gambar di-hosting di layanan **Cloudinary**.
2.  **URL publik** dari gambar di Cloudinary tersebut disimpan sebagai `String` di Firebase Realtime Database bersama dengan data produk lainnya.
3.  Aplikasi kemudian menggunakan *library* **Coil** untuk memuat dan menampilkan gambar secara dinamis dari URL Cloudinary tersebut.

## ✨ Fitur Utama

Aplikasi ini menyediakan fungsionalitas yang berbeda berdasarkan peran pengguna:

* **Autentikasi:** Layar Login dan Registrasi berbasis Firebase Auth.
* **Chat Real-time:** Fungsionalitas obrolan antar pengguna yang didukung oleh Firebase Realtime Database.

### 👤 Peran Customer
* **Dashboard:** Menjelajahi produk dari Firebase.
* **Manajemen Keranjang:** Menambah dan mengelola item di keranjang belanja.
* **Proses Checkout:** Menyimpan pesanan ke Firebase.
* **Riwayat Pesanan:** Mengambil riwayat pesanan dari Firebase.
* **Wishlist & Review:** Menyimpan produk yang diinginkan dan memberikan ulasan.

### 📦 Peran Pengelola (Penjual)
* **Manajemen Produk:** Menambah dan mengedit produk di Firebase, termasuk menyediakan **URL Cloudinary untuk gambar produk**.
* **Manajemen Pesanan:** Melihat dan mengelola pesanan yang masuk dari Firebase.

### ⚙️ Peran Admin
* **Manajemen Pengguna:** Mengelola semua pengguna (peran, profil) di Firebase.
* **Tambah Supervisor:** Menambahkan pengguna baru dengan peran supervisor ke Firebase.

### 📊 Peran Leader & Supervisor
* **Dashboard Analitik:** Melihat ringkasan data dan grafik dari data Firebase.
* **Panel Supervisor:** Panel kontrol khusus untuk supervisor.

## 🛠️ Teknologi yang Digunakan

* **Bahasa:** Kotlin
* **UI:** Jetpack Compose
* **Backend & Database:** **Firebase**
    * **Firebase Realtime Database** (untuk semua data aplikasi)
    * **Firebase Authentication** (untuk manajemen pengguna)
* **Image Hosting:** **Cloudinary** (disimpan sebagai URL)
* **Image Loading:** **Coil** (untuk memuat gambar dari URL)
* **Navigasi:** Compose Navigation

## 🚀 Instalasi dan Penyiapan

Untuk menjalankan proyek ini secara lokal, ikuti langkah-langkah berikut:

1.  **Clone Repositori**
    ```bash
    git clone [https://github.com/faizaqeel07/ecommerceproject]
    ```
2.  **Buka di Android Studio**
    * Buka Android Studio dan pilih "Open an existing project".
    * Arahkan ke direktori tempat Anda meng-clone repositori.

3.  **Siapkan Firebase (Langkah Kritis)**
    * Buka [Firebase Console](https://console.firebase.google.com/).
    * Buat proyek Firebase baru.
    * Tambahkan aplikasi Android ke proyek Firebase Anda dengan nama paket (package name) `com.example.ecommerceproject`.
    * Unduh file `google-services.json` yang dihasilkan.
    * **Ganti** file `app/google-services.json` di dalam proyek dengan file yang baru saja Anda unduh.
    * Di Firebase Console, aktifkan layanan berikut:
        * **Authentication** (Aktifkan metode Sign-in Email/Password).
        * **Realtime Database**.
    * **PENTING:** Buka tab **Realtime Database** dan ubah Aturan Keamanan (Security Rules) agar dapat dibaca/ditulis (untuk pengembangan):
      ```json
      {
        "rules": {
          ".read": true,
          ".write": true
        }
      }
      ```
      *(Catatan: Untuk produksi, Anda harus menggunakan aturan keamanan yang lebih ketat).*

4.  **Sync dan Jalankan**
    * Tunggu Android Studio menyelesaikan sinkronisasi Gradle.
    * Pilih emulator atau perangkat fisik, lalu klik "Run".

## 🤝 Kontribusi

Kontribusi sangat diterima! Silakan fork repositori ini dan ajukan *pull request* untuk setiap perbaikan atau penambahan fitur.
