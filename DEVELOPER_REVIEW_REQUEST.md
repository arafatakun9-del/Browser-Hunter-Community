# Browser Hunter — Request for Independent Developer Review

Hello Android developers,

I am building **Browser Hunter**, a lightweight Android browser. I am a beginner working mainly from an Android phone, so I am asking experienced developers to inspect the actual implementation, point out risks, recommend practical improvements, and contribute focused fixes where possible.

This is not a claim that the app is finished. I want honest technical feedback, including criticism.

## What I am trying to build

1. **Fast, lightweight browser:** native Android Chromium WebView only. Please do not add Firefox or another browser engine.
2. **4 GB RAM target:** particularly entry-level Android devices such as Realme C71. Keep startup, memory use, battery use and APK size under control.
3. **Simple interface:** phone-first, minimal clutter, subtle A. M. Arafat branding.
4. **Desktop mode:** retain desktop-site support without creating unnecessary windows or tabs.
5. **Tab memory management:** hibernate inactive tabs, save enough state to restore them, and avoid keeping many live WebViews.
6. **Downloader is the main priority:** direct downloads for PDF, ZIP, APK, images, audio and video; queue; pause/resume/cancel; background transfer; interruption recovery; clear failure reasons; file-integrity checks.
7. **Media links:** investigate lawful, technically reliable share-link support. Do not promise universal downloads. Protected/DRM/login-only streams and sites that do not expose a direct media URL may be unsupported.
8. **Background playback:** evaluate reliable, policy-compliant audio playback without unnecessary background resource use.
9. **Embedded Gemini assistant:** lightweight/stateless design, sensible quotas and robust errors. An API key inside an APK is extractable; recommend a protected backend or restricted key architecture.
10. **Privacy and security:** safe WebView configuration, SSL error handling, private browsing/cookie boundaries, history clearing, recent-app preview protection, biometric/PIN design, secure storage and careful permission handling.
11. **Practical release quality:** Bengali/English usability, accessibility, crash handling, current Android compatibility and real-device tests.

## Highest-priority review areas

- HTTP Range/206 correctness, redirects, servers ignoring Range, authentication/cookies, duplicate filenames, large files, low storage, network loss, cancellation races, process death and corrupt-file prevention.
- Foreground-service and notification behavior on modern Android versions.
- WebView lifecycle, renderer crashes, tab restoration and memory pressure.
- SSL errors, Safe Browsing, HTTPS downgrade, incognito data separation, storage and API-key handling.
- Gemini model/API availability, quotas, cancellation and key protection.
- Real-device performance on a 4 GB RAM phone.
- Whether each feature is implemented, partial, unsupported, or actually tested.

## How to help

- Open an issue with device/Android version, reproduction steps, expected behavior and logs where possible.
- Submit small, focused pull requests with tests.
- Explain the technical reason for a proposed change.
- Inspect existing code before replacing components.
- Flag security or privacy problems before adding features.
- Clearly state what you tested and what remains unverified.

Please do not commit credentials, API keys, passwords, keystores, personal data, private photos or tokens. Do not add a heavy runtime or another browser engine without a clear cost/benefit analysis.

Thank you for helping make Browser Hunter genuinely useful, stable and safe.
