# Cutout privacy

Effective date: October 17, 2026. Cutout is developed by kigya (Kirill Borichevskiy) for Android and iOS. Contact: kirillborichevskiy@gmail.com.

Cutout removes image backgrounds on your device. Images, clipboard content, EXIF data, and editing masks are not uploaded for processing. There is no account or cloud image-processing API.

Working images are stored in private temporary/cache files. Clear app storage or remove the app to delete its private data. Files you explicitly save and images you copy belong to their respective system storage and clipboard lifecycle.

## Usage data and crash reports

Usage analytics and crash reporting use Google Firebase and are enabled by default. An in-app notice explains this and links to Settings and this policy. You can turn off either control separately in Settings without losing image processing, editing, copying or saving. This pre-release update initializes both controls to enabled once; choices made after that initialization remain saved. Usage events describe actions such as processing, saving, and opening a screen. These SDKs can send app/device information, SDK-generated identifiers, and technical diagnostics to Google; network requests also expose connection information such as the IP address to the receiving service. Cutout excludes media, filenames, paths, clipboard values, and email addresses from product events. Advertising identifier collection and advertising consent are disabled.

You can stop future collection in Settings. Disabling crash reporting also discards unsent reports. This does not erase reports already received by Google. Firebase's retention and processing are described in [Firebase privacy information](https://firebase.google.com/support/privacy); the project's provider-side retention and deletion settings have not been independently verified. Contact the developer with a privacy request without attaching images or other sensitive content.

Remote Config reads a small set of UX settings while usage analytics is enabled. Offline defaults preserve the image-processing workflow.

## Permissions and feedback

Android overlay permission is used only for the optional Bubble, which is off by default. Overlay access is required when you enable Bubble; the main app works without it. Notification and manufacturer background settings are optional. System pickers avoid broad photo-library access. Older Android versions may request write permission when saving; denial offers the system document picker. On iOS, adding a result to Photos requests add-only access when you save. If access is denied, the app explains how to change it in system settings. Permission request attempts and dismissing the diagnostics notice are remembered locally to avoid repeated prompts.

When you press Send in the feedback form, your reply email address, message, app identifier, version and platform are sent to an app-specific Google Apps Script endpoint and forwarded to kirillborichevskiy@gmail.com. Images, clipboard contents and automatic diagnostic attachments are not included. The receiving service sees connection information such as your IP address. The endpoint uses a temporary submission ID for duplicate prevention and a hash of the reply email for rate limiting; these cache entries expire within six hours and ten minutes respectively. Daily quota counters contain a date and count. Provider-side execution-log retention has not been independently verified. Support messages remain in the developer's mailbox until deleted; contact the developer to request deletion. An explicit fallback can open your email app if delivery cannot be confirmed. A timeout does not prove that a message was not delivered; a retry may produce a duplicate.

After repeated successful exports, Cutout may ask the system to show an optional store review prompt. The request counter and cooldown are stored locally. On Android the Google Play In-App Review service handles any rating or review you choose to submit; on iOS this is handled by Apple's store review interface. A submitted review may be public under the relevant store's policies. Cutout does not receive your chosen rating from these system dialogs and does not require a review to continue. See [Google Play review data safety](https://developer.android.com/guide/playcore/in-app-review#data_safety) and the applicable store privacy information.

There is no age-verification or account system. Do not include sensitive personal information in feedback. Store age ratings and audience declarations are determined separately before store distribution.

## This policy website

This page is hosted by GitHub Pages. It has no analytics, advertising, cookies added by Cutout, scripts or forms. GitHub receives normal web-request information when you open the page; its handling is described in the [GitHub privacy statement](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement). The app opens the fixed policy address only when you tap its link and does not append personal or device data.

## Changes and questions

Updates to this policy will appear at [Cutout privacy](https://kigya.github.io/cutout/privacy/) with a revised effective date. Contact kirillborichevskiy@gmail.com about access, deletion or other privacy questions. Provider records may not be identifiable from an email address alone; we will explain what information is available and any limits on fulfilling a request.
