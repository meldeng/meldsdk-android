# MeldSDK Android example

A minimal, runnable demo of MeldSDK (same flow as the iOS and web demos): a styled checkout card,
a live quote, then the Mercuryo widget with a status banner + event log underneath.

Where to look:
- `MainActivity.kt` — `CheckoutScreen` (the SDK touchpoints: `configure` / `capabilities`) and
  `WidgetScreen` (the actual integration: `Meld.mount(...)` + event handling).
- `OrderService.kt` — **POC-only**: creates the order by calling Meld directly. In a real app your
  backend does this so the API key never ships in the app.

## Credentials

The demo reads credentials from `local.properties` (gitignored) or environment variables of the
same name. Add to `local.properties` at the **repo root**:

```properties
MELD_API_KEY=your_sandbox_key
MELD_CUSTOMER_ID=customer_with_APPROVED_kyc
# Optional — API host (no scheme). Defaults to api-sb.meld.io (sandbox). Use api-qa.meld.io for QA.
MELD_API_HOST=api-qa.meld.io
```

These are injected into `BuildConfig` at build time, so re-build after changing them. The app
builds without credentials (you just can't create an order until they're set).

## Run

Open the repo in Android Studio and run the **example** configuration on an emulator or device,
or from the command line:

```bash
./gradlew :example:installDebug
```

The widget uses the camera for KYC; the app requests the `CAMERA` permission on first launch.

> Settlement is the Meld webhook, never a client event. `onStatusChange` with `completed` is the
> provider's "order complete", not settlement — see the SDK README.
