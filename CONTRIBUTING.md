# Contributing

Thank you for considering a contribution to Browser Hunter.

## Before starting

1. Read [the developer review request](DEVELOPER_REVIEW_REQUEST.md).
2. Check existing issues and pull requests.
3. For major changes, open an issue first and discuss the approach.

## Project constraints

- Android native app using the system Chromium WebView.
- Do not add Firefox or a second browser engine.
- Keep the 4 GB RAM / low-storage target in mind.
- Downloader correctness and safety are higher priority than adding cosmetic features.
- Do not claim universal support for social-media or protected video streams.
- Do not commit secrets, API keys, keystores, passwords, tokens, private photos or personal files.

## Pull request expectations

- Keep changes focused and explain the problem being solved.
- Add or update tests for behavior changes.
- Include relevant build/test results.
- Mention device model and Android version for device testing.
- Document unsupported cases and trade-offs.
- Avoid unrelated formatting churn.

## Security reports

Do not publish exploitable vulnerabilities or private user data in a public issue. Share a minimal, responsible report with the maintainer and allow time for investigation before public disclosure.

## License status

A project license has not yet been selected. Please do not assume that code reuse or redistribution is authorized until a license is explicitly added.
