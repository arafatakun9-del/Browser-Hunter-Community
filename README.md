# Browser Hunter — Community Edition

**Browser Hunter** is an Android browser project focused on lightweight Chromium WebView browsing, a practical download manager, and a clean phone-first experience.

- **Public source repository:** https://github.com/arafatakun9-del/Browser-Hunter-Community
- **Product name:** Browser Hunter (please keep this name in community builds and derivative versions)
- **Original development repository:** remains private and is not exposed here.

## Project direction

The project owner welcomes Android developers and open-source contributors who want to use this code to build, improve, or rebuild the app closer to the original requirements. You may adapt the code under the MIT license. If you publish a rebuilt or derivative app from this project, please keep the product name **Browser Hunter** and preserve the intended direction below.

- Native Android Chromium WebView only — no Firefox or additional browser engine.
- Fast, lightweight browsing for modest Android phones, with 4 GB RAM devices as an important target.
- Clean, phone-first interface with desktop-site mode.
- Background-tab hibernation to reduce memory use.
- A dependable download manager for supported direct files (PDF, ZIP, APK, images, audio and video), with queue and pause/resume/cancel support where available.
- Media extraction is best-effort and site-dependent. No promise of universal YouTube, Facebook, or TikTok downloads.
- Privacy, security, accessibility, and Bengali-friendly usability.

## Build the Android app

1. Open this repository in Android Studio.
2. Use JDK 17 and the Gradle version configured by the project workflow.
3. Allow Android Studio to sync dependencies.
4. Build the debug variant from the app module.

The GitHub Actions workflow is configured to run unit tests and build a debug APK on pushes to main and arafat/**, and on manual dispatch. Check the Actions tab for the latest build result and downloadable artifacts.

## Current status

This repository now contains a sanitized public source snapshot. Personal portrait images, personal launcher artwork, and private AI Studio uploads were intentionally excluded and replaced with neutral Browser Hunter artwork. The original private repository has not been made public.

A successful CI build is not proof that every feature is complete, secure, or compatible with every physical device. The APK has not yet been published as a verified public release. Please do not treat the source repository itself as an APK download.

## Contribute

Independent Android developers are warmly invited to review, improve, rebuild, and submit focused pull requests. You do not need to preserve the current implementation if a better, stable implementation meets the project goals; keep the app name **Browser Hunter**.

- [Developer review request](DEVELOPER_REVIEW_REQUEST.md)
- [Contribution guide](CONTRIBUTING.md)
- [Known limitations and verification checklist](KNOWN_LIMITATIONS.md)
- [Project handoff](PROJECT_HANDOFF.md)
- [Community permission and project direction](COMMUNITY_PERMISSION.md)

Please prioritize correctness, battery/RAM use, privacy, and real-device testing over adding features that cannot be reliably supported.
