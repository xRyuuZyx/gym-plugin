# AutoGym

Plugin gym otomatis untuk Paper 1.21.1+ (termasuk server Paper 1.21.x) dan Java 21. Build dibuat dengan Paper API 1.21.1 untuk menjaga kompatibilitas ke depan pada seri 1.21.x.

## Fitur

- Klik kanan Paper dengan CustomModelData yang cocok untuk memulai sesi otomatis; tidak ada tombol gym yang perlu ditekan berulang kali.
- HUD berupa judul latihan di tengah atas dan progress bar 0–100% di action bar.
- Tiga latihan: Bench Press memberi Strength +0.1, Treadmill memberi Agility +0.1, dan Pull-Up memberi Defense +0.1.
- Level disimpan per UUID secara permanen di `plugins/AutoGym/player-data.yml`, mulai 0.0 dan dibatasi maksimum 50.0.
- Sesi dibatalkan jika pemain keluar atau mati; sesi yang tidak selesai tidak memberi stat.
- Pemain dapat menoleh selama sesi. Pergerakan karakter dapat dikunci melalui konfigurasi.

## Instalasi

1. Gunakan Java 21 dan Paper 1.21.1 atau lebih baru pada seri 1.21.x.
2. Salin `target/AutoGym-1.0.0.jar` ke folder `plugins/` server.
3. Restart server. Ubah `plugins/AutoGym/config.yml` sesuai kebutuhan, lalu jalankan `/gym reload`.

## Perintah

- `/gym stats` — menampilkan Strength, Agility, dan Defense.
- `/gym give <benchpress|treadmill|pullup> [pemain]` — memberi item pemicu gym.
- `/gym reload` — memuat ulang konfigurasi.

`/gym give` dan `/gym reload` memerlukan permission `gym.admin` (default: operator).

## CustomModelData

Default config menggunakan material `PAPER` dan ID 10001 (Bench Press), 10002 (Treadmill), 10003 (Pull-Up). Sesuaikan ID tersebut dengan resource pack/model yang dipakai. Item bisa dibuat dengan `/gym give <tipe>`; command ini mengisi material dan CustomModelData sesuai config.

## Batasan perspektif kamera

Paper API tidak menyediakan kontrol untuk mengganti opsi perspektif kamera lokal pemain ke third-person front (F5) secara paksa. Perspektif first/third-person adalah pilihan sisi klien, sehingga plugin server murni ini tidak dapat menjamin perubahan kamera. HUD, progres otomatis, pembatasan gerak, dan stat tetap berjalan; untuk memaksa kamera diperlukan mod/client-side companion yang kompatibel dengan pemain.

## Build dari source

```bash
mvn clean package
```

JAR hasil build: `target/AutoGym-1.0.0.jar`.
