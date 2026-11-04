# Browser Hunter — Public Project Handoff

## Product brief

Build a practical, lightweight Android browser whose standout feature is a dependable download manager.

## Fixed requirements

- Android native Chromium WebView only. No Firefox or additional browser engine.
- Low RAM/storage/battery footprint; primary test target is a 4 GB RAM Android phone.
- Clean, phone-first UI with minimal clutter.
- Desktop-site mode remains available.
- Background tabs should be hibernated and restored efficiently.
- Downloader priority: direct PDF, ZIP, APK, image, audio and video URLs; queue; pause/resume/cancel; background operation; interruption recovery; meaningful failure reasons; file-integrity validation.
- Social-media share-link extraction is best-effort only. Never advertise universal downloading; respect DRM, authentication, site restrictions and technical limitations.
- Lightweight embedded Gemini assistant; do not expose an unrestricted API key in a public client.
- Privacy/security review: safe WebView configuration, SSL error handling, private browsing data boundaries, history clearing, app-switcher preview protection, secure metadata and API-key design.
- Bengali-friendly explanations and phone-friendly install instructions.

## Current verified CI information from the private development repository

A debug CI run (#85) previously passed the testDebugUnitTest and assembleDebug tasks. It produced a GitHub Actions debug APK artifact. Release build was skipped because release signing secrets were not configured. This is not a production release and does not prove physical-device QA.

Original private development repository and branch are intentionally not disclosed here. The public copy must be reviewed and sanitized before source publication.

## Known engineering caveats

- No yt-dlp runtime is bundled; YouTube downloading is not a reliable supported feature.
- Facebook/TikTok extraction may only work when a public direct media URL is exposed; protected or segmented streams may fail.
- A release-metadata check is not an installed or updateable extractor.
- API keys embedded in APKs can be extracted.
- CI success is not proof of real-device stability or complete privacy/security behavior.

## Review priorities

1. Download correctness and corrupt-file prevention.
2. Foreground service and notifications on modern Android.
3. WebView lifecycle, memory pressure and tab restoration.
4. SSL, Safe Browsing, HTTPS downgrade, incognito isolation and key handling.
5. Gemini model availability, quotas and safe deployment.
6. Accessibility, localization and real-device QA.

## Public repository hygiene

Before source or APK publication:
- Remove personal portraits and private AI Studio uploads; use neutral placeholder artwork.
- Scan the entire history and current tree for secrets, credentials, tokens, local configuration and signing material.
- Do not publish private keystores or passwords.
- Confirm licenses for dependencies and choose a project license before inviting code reuse.
- Publish only a sanitized source snapshot and clearly label the APK as debug or release.
