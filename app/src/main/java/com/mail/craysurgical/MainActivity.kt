package com.mail.craysurgical

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.firebase.messaging.FirebaseMessaging
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class MainActivity : AppCompatActivity() {

    // ✅ Your cPanel webmail URL
    private val WEBMAIL_URL = "https://craysurgical.com:2096/"

    // ✅ Your server endpoint (public_html/api/save_token.php)
    private val TOKEN_ENDPOINT_URL = "https://craysurgical.com/api/save_token.php"

    // ✅ Only these accounts will be subscribed from THIS device (privacy)
    // You can later change this list from a settings screen, but for now it’s fixed.
    private val EMAILS_USED_ON_THIS_DEVICE = listOf(
        "info@craysurgical.com",
        "sale@craysurgical.com",
        "sales@craysurgical.com",
        "customerservice@craysurgical.com"
    )

    private lateinit var webView: WebView

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(this)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            this,
            "secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // ✅ When phone locks (screen off + keyguard), clear active WebView session
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_SCREEN_OFF) return
            if (isPhoneLocked()) clearWebViewSession()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        setupWebView(webView)

        requestNotificationPermissionIfNeeded()

        onBackPressedDispatcher.addCallback(this) {
            if (webView.canGoBack()) webView.goBack() else finish()
        }

        // ✅ 1) Get FCM token
        // ✅ 2) Send token + emails list to server (creates tokens_<email>.txt files)
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                android.util.Log.d("FCM_TOKEN", token)
                sendTokenAndEmailsToServer(token, EMAILS_USED_ON_THIS_DEVICE)
            }
            .addOnFailureListener { e ->
                android.util.Log.e("FCM_TOKEN", "Failed to get FCM token", e)
            }

        val biometricsEnabled = prefs.getBoolean("biometrics_enabled", false)

        if (biometricsEnabled) {
            promptBiometric(
                onSuccess = {
                    restoreCookiesFromStorage()
                    webView.loadUrl(WEBMAIL_URL)
                },
                onFailOrCancel = { finish() }
            )
        } else {
            webView.loadUrl(WEBMAIL_URL)
        }
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }

    override fun onStop() {
        super.onStop()
        runCatching { unregisterReceiver(screenReceiver) }
    }

    /**
     * Sends:
     *  token=<FCM_TOKEN>
     *  emails=info@...,sale@...,sales@...,customerservice@...
     */
    private fun sendTokenAndEmailsToServer(token: String, emails: List<String>) {
        Thread {
            try {
                val emailsCsv = emails.joinToString(",")

                val body =
                    "token=" + URLEncoder.encode(token, "UTF-8") +
                            "&emails=" + URLEncoder.encode(emailsCsv, "UTF-8")

                val conn = (URL(TOKEN_ENDPOINT_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 15000
                    readTimeout = 15000
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    setRequestProperty("Content-Length", body.toByteArray().size.toString())
                }

                conn.outputStream.use { it.write(body.toByteArray()) }

                val code = conn.responseCode
                val responseText = try {
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    stream?.bufferedReader()?.use { it.readText() } ?: ""
                } catch (_: Exception) {
                    ""
                }

                conn.disconnect()

                android.util.Log.d("TOKEN_POST", "HTTP=$code response=$responseText")
            } catch (e: Exception) {
                android.util.Log.e("TOKEN_POST", "Failed to send token+emails", e)
            }
        }.start()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        val granted = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

        if (!granted) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }

    private fun baseUrl(): String {
        val uri = Uri.parse(WEBMAIL_URL)
        return "${uri.scheme}://${uri.host}/"
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(wv: WebView) {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(wv, true)

        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.databaseEnabled = true
        wv.settings.javaScriptCanOpenWindowsAutomatically = true
        wv.settings.loadsImagesAutomatically = true
        wv.settings.mediaPlaybackRequiresUserGesture = false
        wv.settings.cacheMode = WebSettings.LOAD_DEFAULT
        wv.settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        wv.webChromeClient = WebChromeClient()
        wv.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return false
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)

                val cookies = CookieManager.getInstance().getCookie(baseUrl()).orEmpty()
                if (cookies.isNotBlank()) {
                    storeCookiesToStorage(cookies)
                    prefs.edit().putBoolean("biometrics_enabled", true).apply()
                }
            }
        }
    }

    private fun promptBiometric(onSuccess: () -> Unit, onFailOrCancel: () -> Unit) {
        val executor = ContextCompat.getMainExecutor(this)

        val prompt = BiometricPrompt(
            this,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onFailOrCancel()
                }
            }
        )

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Cray Surgical")
            .setSubtitle("Unlock mail with fingerprint")
            .setNegativeButtonText("Cancel")
            .build()

        prompt.authenticate(info)
    }

    private fun storeCookiesToStorage(cookies: String) {
        prefs.edit().putString("web_cookies", cookies).apply()
    }

    private fun restoreCookiesFromStorage() {
        val cookies = prefs.getString("web_cookies", null) ?: return

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)

        val base = baseUrl()

        cm.removeAllCookies(null)
        cm.flush()

        val cookiePairs = cookies.split("; ")
        for (pair in cookiePairs) {
            cm.setCookie(base, "$pair; Path=/")
        }
        cm.flush()
    }

    private fun clearWebViewSession() {
        val cm = CookieManager.getInstance()
        cm.removeAllCookies(null)
        cm.flush()

        WebStorage.getInstance().deleteAllData()

        runCatching {
            webView.clearCache(true)
            webView.clearHistory()
        }
    }

    private fun isPhoneLocked(): Boolean {
        val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        return km.isKeyguardLocked
    }
}
