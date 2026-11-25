# Pemutar Video (Android)

Pemutar video sederhana tanpa iklan, dibuat dengan Kotlin dan AndroidX Media3 (ExoPlayer).
Bisa dipilih sebagai aplikasi pemutar video default.

## Fitur

- Daftar video dari penyimpanan HP, tampilan Video dan Folder, cari, urutkan
- Thumbnail, durasi, resolusi, dan penanda sudah ditonton sebagian
- Lanjut dari posisi terakhir secara otomatis
- Daftar putar otomatis dari folder yang sama (video berikutnya/sebelumnya)
- Gestur: ketuk dua kali kiri/kanan = mundur/maju 10 detik, geser vertikal kiri = kecerahan,
  geser vertikal kanan = volume, geser horizontal = seek
- Kecepatan putar, pilih trek audio, pilih subtitle (lewat ikon pengaturan dan CC)
- Muat subtitle eksternal (.srt, .vtt, .ass, .ttml)
- Rasio layar (Fit / Fill / Zoom), rotasi layar, kunci layar, jendela mengambang (PiP)
- Buka file lain dan buka URL jaringan (http/https, HLS, DASH, RTSP)
- Muncul di "Buka dengan" untuk video dari file manager, galeri, atau browser

## Cara membuat APK

### Dari HP, tanpa komputer (GitHub Actions)

1. Buat repositori baru di github.com, lalu unggah seluruh isi folder ini
   (termasuk folder `.github`).
2. Buka tab **Actions** > **Build APK** > **Run workflow**.
3. Setelah selesai (sekitar 5 menit), buka hasil jalannya lalu unduh artefak `PemutarVideo-apk`.
4. Ekstrak zip-nya, pasang `app-release.apk`. Izinkan "Pasang dari sumber tidak dikenal" jika diminta.

### Dari komputer (Android Studio)

1. Buka folder ini di Android Studio (versi terbaru), tunggu Gradle sync.
2. Menu **Build > Build APK(s)**, atau jalankan langsung ke HP.

## Menjadikan pemutar default

Buka video dari File Manager atau Galeri, pilih **Pemutar Video**, lalu ketuk **Selalu**.
Di menu aplikasi ada juga panduan lengkap (**Jadikan pemutar default**).

## Batasan

ExoPlayer memakai decoder bawaan HP. Beberapa codec langka (misalnya audio DTS/AC3 pada
sebagian perangkat, atau video AVI/MKV lama) bisa gagal diputar. Untuk cakupan format
seluas VLC, aplikasi ini bisa dialihkan ke libVLC.
