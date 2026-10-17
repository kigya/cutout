# Cutout privacy

Effective date: October 17, 2026. Cutout is developed by kigya (Kirill Borichevskiy) for Android and iOS. Contact: kirillborichevskiy@gmail.com.

Cutout removes image backgrounds on your device. Images, clipboard content, EXIF data, and editing masks are not uploaded for processing. There is no account or cloud image-processing API.

Working images are stored in private temporary/cache files. Clear app storage or remove the app to delete its private data. Files you explicitly save and images you copy belong to their respective system storage and clipboard lifecycle.

## Optional diagnostics

Usage analytics and crash reporting use Google Firebase and are disabled until you enable each control in Settings. Usage events describe actions such as processing, saving, and opening a screen. These SDKs can send app/device information, SDK-generated identifiers, and technical diagnostics to Google; network requests also expose connection information such as the IP address to the receiving service. Cutout excludes media, filenames, paths, clipboard values, and email addresses from product events. Advertising identifier collection and advertising consent are disabled.

You can stop future collection in Settings. Disabling crash reporting also discards unsent reports. This does not erase reports already received by Google. Firebase's retention and processing are described in [Firebase privacy information](https://firebase.google.com/support/privacy); the project's provider-side retention and deletion settings have not been independently verified. Contact the developer with a privacy request without attaching images or other sensitive content.

Optional Remote Config reads a small set of UX settings after analytics opt-in. Offline defaults preserve the image-processing workflow.

## Permissions and feedback

Android overlay permission is used only for the optional Bubble. Notification and manufacturer background settings are optional. System pickers avoid broad photo-library access. Older Android versions may request write permission when saving; denial offers the system document picker. On iOS, adding a result to Photos requests add-only access when you save.

Feedback opens your email app without attaching images. An email is sent only when you choose to send it. Contact: kirillborichevskiy@gmail.com.

There is no age-verification or account system. Do not include sensitive personal information in feedback. Store age ratings and audience declarations are determined separately before store distribution.
