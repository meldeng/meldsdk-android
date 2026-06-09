package io.meld.demo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import io.meld.sdk.Meld
import io.meld.sdk.MeldEnvironment
import io.meld.sdk.MeldEventHandlers
import io.meld.sdk.MeldOrder
import io.meld.sdk.MeldStatus
import io.meld.sdk.MeldWidgetHandle
import kotlinx.coroutines.launch

// A minimal example app for MeldSDK.
//
// Where to look:
//   • CheckoutScreen   — the checkout UI and the SDK touchpoints (configure / capabilities)
//   • WidgetScreen     — the actual integration: Meld.mount(...) + event handling
//   • OrderService.kt  — POC-only: creates the order by calling Meld directly. In a real app your
//                        backend does this so the API key never ships in the app.
// Anything tagged "demo-only" (status banner, event log, auto-close) is just for this example.

private val Bg = Color(0xFF2B2B28)
private val Card = Color(0xFFF1F0EC)
private val Panel = Color(0xFFE6E5DF)
private val Green = Color(0xFF3E6650)
private val Ink = Color(0xFF15191F)
private val SubInk = Color(0xFF6B7280)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // KYC inside the widget needs the camera; ask up front so WebChromeClient can grant it.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 0)
        }
        setContent { App() }
    }
}

@Composable
private fun App() {
    val events = remember { EventLogState() }
    var presented by remember { mutableStateOf<MeldOrder?>(null) }

    Surface(modifier = Modifier.fillMaxSize(), color = Bg) {
        val order = presented
        if (order == null) {
            CheckoutScreen(events) { presented = it }
        } else {
            WidgetScreen(order, events) { presented = null }
        }
    }
}

// MARK: - Checkout

@Composable
private fun CheckoutScreen(events: EventLogState, onPresent: (MeldOrder) -> Unit) {
    val orders = remember { OrderService() }
    val scope = rememberCoroutineScope()

    var wallet by remember { mutableStateOf(DemoConfig.DEFAULT_WALLET) }
    var customerId by remember { mutableStateOf("") }
    var clientIP by remember { mutableStateOf<String?>(null) }
    var receiveText by remember { mutableStateOf("…") }
    var quoteNote by remember { mutableStateOf("fetching live quote…") }
    var rateText by remember { mutableStateOf("Credit / debit card rail") }
    var errorText by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }

    val needsCustomerField = DemoConfig.meldCustomerId.isEmpty()
    val buyDisabled = creating || wallet.isBlank()

    // On appear: configure the SDK and fetch a live quote to display.
    LaunchedEffect(Unit) {
        Meld.configure(MeldEnvironment.SANDBOX) // SDK: one-time setup
        if (DemoConfig.meldApiKey.isEmpty()) {
            receiveText = "≈ —"
            quoteNote = "MELD_API_KEY not set"
            errorText = "MELD_API_KEY is empty. Fill local.properties (see README)."
            return@LaunchedEffect
        }
        clientIP = orders.publicIP()
        try {
            val quote = orders.quote()
            quote.destinationAmount?.let { receiveText = "≈ ${format(it)}" }
            quote.totalFee?.let { quoteNote = "live quote — total fees ${format(it)} ${DemoConfig.SOURCE_CURRENCY}" }
            quote.exchangeRate?.let { rateText = "1 BTC ≈ ${it.toLong()} ${DemoConfig.SOURCE_CURRENCY}" }
        } catch (e: Exception) {
            receiveText = "≈ —"
            quoteNote = "quote failed: ${e.message}"
        }
    }

    fun buy() {
        errorText = ""
        creating = true
        scope.launch {
            try {
                if (DemoConfig.meldApiKey.isEmpty()) {
                    errorText = "Set MELD_API_KEY in local.properties (see README)."
                    return@launch
                }
                val customer = if (needsCustomerField) customerId.trim() else DemoConfig.meldCustomerId
                if (customer.isEmpty()) {
                    errorText = "Set a Meld customer ID."
                    return@launch
                }
                val orderJSON = orders.createOrder(customer, wallet.trim(), clientIP)
                val order = MeldOrder.fromJson(orderJSON) // SDK: decode the order
                if (!Meld.capabilities(order).embeddable) { // SDK: can we embed it?
                    errorText = "Order is not embeddable by this SDK (renderMode != IFRAME)."
                    return@launch
                }
                events.clear()
                onPresent(order)
            } catch (e: Exception) {
                errorText = e.message ?: "order creation failed"
            } finally {
                creating = false
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Card, RoundedCornerShape(18.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Buy Bitcoin", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)

            AmountPanel("You pay", DemoConfig.SOURCE_AMOUNT, DemoConfig.SOURCE_CURRENCY)
            AmountPanel("You receive", receiveText, "BTC", note = quoteNote, footer = "By ✦ Mercuryo — $rateText")

            FieldLabel("Wallet Address")
            DemoField(wallet, { wallet = it })
            if (needsCustomerField) {
                FieldLabel("Meld Customer ID")
                DemoField(customerId, { customerId = it }, placeholder = "customer with APPROVED KYC")
            }
            FieldLabel("Payment Method")
            Box(
                Modifier.fillMaxWidth().background(Color(0xFFF7F6F2), RoundedCornerShape(12.dp)).padding(14.dp),
            ) { Text("Credit or debit card", color = Ink) }

            Button(
                onClick = { buy() },
                enabled = !buyDisabled,
                colors = ButtonDefaults.buttonColors(containerColor = Green, disabledContainerColor = Color(0xFFDCDAD4)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(54.dp),
            ) {
                Text(if (creating) "Creating order…" else "Buy Bitcoin", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }

            if (errorText.isNotEmpty()) {
                Text(errorText, color = Color(0xFFB3261E), fontSize = 13.sp)
            }
            Text("Powered by Meld.io", color = SubInk, fontSize = 13.sp, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

@Composable
private fun AmountPanel(label: String, amount: String, currency: String, note: String? = null, footer: String? = null) {
    Column(
        Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(12.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, color = SubInk, fontSize = 15.sp)
                Text(amount, color = Ink, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            }
            Text(currency, color = Ink, fontWeight = FontWeight.Bold)
        }
        note?.let { Text(it, color = SubInk, fontSize = 13.sp) }
        footer?.let { Text(it, color = Ink, fontSize = 13.sp) }
    }
}

@Composable
private fun FieldLabel(text: String) = Text(text, color = Ink, fontSize = 16.sp)

@Composable
private fun DemoField(value: String, onChange: (String) -> Unit, placeholder: String = "") {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text(placeholder, color = SubInk) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
}

// MARK: - Widget screen (the actual SDK integration)

@Composable
private fun WidgetScreen(order: MeldOrder, events: EventLogState, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(Ink).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onClose) { Text("Back", color = Color.White) }
            Text("Mercuryo", color = Color.White, fontWeight = FontWeight.Bold)
        }

        // SDK: mount the order into a container view, passing your event handlers; unmount on dispose.
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { ctx ->
                val container = FrameLayout(ctx)
                try {
                    container.tag = Meld.mount(order, container, events.handlers(onClose))
                } catch (e: Exception) {
                    events.record("mount failed: ${e.message}")
                }
                container
            },
            onRelease = { container ->
                (container.tag as? MeldWidgetHandle)?.unmount() // SDK: tear down
            },
        )

        // demo-only: status banner + event log
        StatusBanner(events.status)
        EventLogView(events.lines)
    }
}

@Composable
private fun StatusBanner(status: MeldStatus?) {
    status ?: return
    val (title, color) = when (status) {
        MeldStatus.PENDING -> "Processing payment…" to Color(0xFF888888)
        MeldStatus.COMPLETED -> "Order complete — settlement via webhook" to Color(0xFF2E7D32)
        MeldStatus.FAILED -> "Order failed" to Color(0xFFC62828)
        MeldStatus.CANCELLED -> "Order cancelled" to Color(0xFF888888)
    }
    Text(
        title,
        color = color,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth().background(color.copy(alpha = 0.12f)).padding(12.dp),
    )
}

@Composable
private fun EventLogView(lines: SnapshotStateList<String>) {
    Column(
        Modifier
            .fillMaxWidth()
            .height(140.dp)
            .background(Color(0xFF0A1221))
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
    ) {
        if (lines.isEmpty()) {
            Text("waiting for events…", color = Color(0xFF9AA0A8), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        } else {
            lines.forEach { Text(it, color = Color(0xFFD9D9D9), fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
        }
    }
}

// MARK: - demo-only event log + handlers

private class EventLogState {
    val lines: SnapshotStateList<String> = mutableStateListOf()
    var status by mutableStateOf<MeldStatus?>(null)
    private var closed = false
    private val main = Handler(Looper.getMainLooper())

    fun record(line: String) {
        lines.add(line)
    }

    fun clear() {
        lines.clear()
        status = null
        closed = false
    }

    // SDK: react to the widget's lifecycle. Here we log each event, drive the status banner, and
    // (for the demo) close the screen on a terminal outcome.
    fun handlers(onClose: () -> Unit) = MeldEventHandlers(
        onReady = { record("onReady") },
        onPaymentSubmitted = { record("onPaymentSubmitted (UX hint, not settled)") },
        onStatusChange = { e ->
            status = e.status
            record("onStatusChange: ${e.status.raw} (${e.providerStatus ?: "-"})")
            if (e.status == MeldStatus.COMPLETED) finish("completed", onClose)
            if (e.status == MeldStatus.FAILED) finish("failed", onClose)
        },
        onCancel = {
            status = MeldStatus.CANCELLED
            record("onCancel")
            finish("cancelled", onClose)
        },
        onError = { e ->
            status = MeldStatus.FAILED
            record("onError [${e.code}] ${e.message}")
            finish("error", onClose)
        },
    )

    // demo-only: close the screen once, shortly after a terminal event, so the outcome shows.
    private fun finish(reason: String, onClose: () -> Unit) {
        if (closed) return
        closed = true
        record("→ closing widget ($reason)")
        main.postDelayed({ onClose() }, 1500)
    }
}

/** Trim trailing zeros so amounts read cleanly (0.00021400 -> 0.000214). */
private fun format(value: Double): String {
    var s = String.format("%.8f", value)
    while (s.contains(".") && (s.endsWith("0") || s.endsWith("."))) s = s.dropLast(1)
    return s
}
