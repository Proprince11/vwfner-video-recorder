# VWFNDR Video Recorder 📹

A sleek, premium, dedicated video recording Android application built with modern architecture and Google's CameraX library.

## Key Features ✨
- **1080p @ 60 FPS Locked:** Configured to strictly record crisp, high-definition `FHD` video at 60 frames per second using direct `Camera2Interop` AE targeting.
- **Premium UI:** Designed with an interface resembling high-end stock camera apps, including a sleek timer, blinking recording indicators, and a record button that elegantly animates into a stop square when active.
- **Broad Device Compatibility:** Supports both modern Android 14+ devices and legacy Android (down to Android 7.0 / API 24) with robust storage permission handling (`WRITE_EXTERNAL_STORAGE` backwards compatibility).
- **Ghost-Recording Protection:** Safely ties video recording to the Android Activity Lifecycle. If you receive a phone call or minimize the app, it instantly stops and saves the video to prevent corrupted files and out-of-sync UI states.

## Under the Hood 🔧
- Built entirely in **Kotlin**.
- Uses **CameraX 1.3+** for robust camera session handling.
- Saves files directly to the public `Movies` MediaStore directory.

## How to use
1. Press the central button to start recording.
2. Hit the switch camera button on the right to flip between front and back lenses.
3. Tap the gallery icon on the left to view your saved recordings.
