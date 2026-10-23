# The JavaScript bridge is called from injected JS by reflection; R8 must not strip or rename it.
-keepclassmembers class io.meld.sdk.** {
    @android.webkit.JavascriptInterface <methods>;
}

# An app may exclude play-services-wallet; readiness then answers Unknown. This is the only class that
# references Wallet or its Tasks, so R8 accepts them missing here and nowhere else.
-dontwarn io.meld.sdk.googlepay.PlayServicesGooglePay

# The SDK leaves out play-services-maps. Wallet references only its LatLng, from the loyalty, offer and
# gift card objects the SDK never builds.
-dontwarn com.google.android.gms.maps.model.LatLng
