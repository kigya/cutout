# Cutout feedback endpoint

This is an app-specific adaptation of the audited feedback baseline. It accepts only `appId: dev.kigya.cutout` and sends text-only feedback to the fixed support address. The app currently uses its email-composer fallback; no web-app endpoint is configured.

Run `node --test support/apps-script/feedback.test.cjs`. Local tests mock Google services and never send mail. Deployment, installed-app delivery and Reply-To verification remain separate acceptance steps. Do not deploy this over another app's endpoint or embed script properties in the app.
