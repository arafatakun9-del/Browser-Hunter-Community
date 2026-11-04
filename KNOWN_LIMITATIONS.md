# Known Limitations and Verification Checklist

This document separates intended behavior from verified behavior. It must be updated as evidence becomes available.

## Known boundaries

- The intended browser engine is Android's native Chromium WebView only.
- A successful GitHub Actions build proves compilation/tests in CI only; it does not prove physical-device reliability.
- Direct-file downloads depend on the site exposing a downloadable URL and the server behaving compatibly.
- YouTube downloading is not a reliable supported feature in the current implementation.
- Facebook/TikTok media extraction, where attempted, is best-effort and can fail for login-protected, DRM, HLS/DASH, or changing site formats.
- A yt-dlp release metadata check is not the same as bundling or updating an extractor runtime.
- Gemini API keys embedded in Android applications can be extracted. A protected backend or strict key restrictions/quotas are needed before public production use.
- Biometric/PIN gating, cookie isolation, recent-app screenshot protection, background playback and all security claims require code review and real-device verification.

## Required QA before a public release

### Downloads
- PDF, ZIP, APK, image, audio and video direct links.
- Range-supported and non-Range servers.
- HTTP 200 vs 206 validation and redirects.
- Large files, zero-byte files, duplicate names and low-storage failures.
- Pause, resume, cancel, network loss and process death.
- No partial/corrupt file marked as complete.

### Android lifecycle
- Foreground service and notifications on current Android versions.
- Notification permission denied.
- App process killed during transfer.
- WebView renderer crash and tab restoration.
- Memory pressure and rapid tab switching on a 4 GB device.

### Security and privacy
- SSL errors cancel safely; no user-bypass of certificate errors.
- Safe Browsing and HTTPS behavior.
- Incognito cookies/history and exit cleanup.
- App-switcher preview behavior.
- API-key storage and quota controls.
- Storage permissions and exported components.

### Release
- Reproducible CI build.
- Stable signing certificate and documented fingerprint.
- No secrets or personal assets in public repository.
- Accessibility and Bengali/English UI review.
- Physical-device smoke test and crash review.
