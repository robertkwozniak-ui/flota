package pl.flota.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.json.JSONObject

/**
 * Jedyny ekran aplikacji: strona ewidencji w WebView.
 *
 * Decyzja, która napędza całą tę aplikację: interfejsu nie przepisujemy.
 * Kierowca widzi dokładnie ten sam ekran, co w przeglądarce telefonu, a
 * aplikacja dokłada to, czego przeglądarka nie potrafi — nagrywanie trasy po
 * zgaszeniu ekranu. Każda poprawka wtyczki trafia do aplikacji sama, bez
 * nowego wydania APK.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var ustawienia: Ustawienia
    private lateinit var widok: WebView
    private lateinit var konfiguracja: View

    private var czekaNaZgode: GeolocationPermissions.Callback? = null
    private var czekaNaZgodeOrigin: String? = null

    override fun onCreate(stan: Bundle?) {
        super.onCreate(stan)
        setContentView(R.layout.activity_main)

        ustawienia = Ustawienia(this)
        widok = findViewById(R.id.widok)
        konfiguracja = findViewById(R.id.konfiguracja)

        przygotujWebView()
        przygotujKonfiguracje()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (konfiguracja.visibility == View.GONE && widok.canGoBack()) {
                    widok.goBack()
                } else {
                    finish()
                }
            }
        })

        // Bez linku z tokenem nie ma czego pokazać — strona ewidencji wymaga
        // tokenu, a sam adres witryny prowadzi do zwykłej strony WordPressa.
        val adres = adresZIntentu(intent) ?: ustawienia.strona

        if (adres.isBlank()) {
            pokazKonfiguracje()
        } else {
            otworz(adres)
        }

        poprosOUprawnienia()
    }

    override fun onNewIntent(nowy: Intent) {
        super.onNewIntent(nowy)
        setIntent(nowy)

        adresZIntentu(nowy)?.let { otworz(it) }
    }

    override fun onResume() {
        super.onResume()

        // Sprawdzenie aktualizacji przy każdym powrocie na ekran — samo
        // pilnuje, by nie pytać sieci częściej niż raz na kilka godzin.
        Aktualizacja.sprawdz(this)

        // Powrót na ekran to dobry moment, by strona dopytała o postoje
        // wykryte w tle i odświeżyła licznik kilometrów.
        if (konfiguracja.visibility == View.GONE) {
            widok.evaluateJavascript(
                "window.ffNatywneOdswiez && window.ffNatywneOdswiez();",
                null
            )
        }
    }

    /* ---------- WebView ---------- */

    @SuppressLint("SetJavaScriptEnabled")
    private fun przygotujWebView() {
        widok.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setGeolocationEnabled(true)
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = false
            displayZoomControls = false

            // Sygnał dla wtyczki: strona wie, że działa w aplikacji.
            userAgentString = "$userAgentString FlotaFirmowa/${BuildConfig.VERSION_NAME}"
        }

        widok.addJavascriptInterface(MostJS(this), MostJS.NAZWA)

        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        widok.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                zapytanie: WebResourceRequest
            ): Boolean {
                val url = zapytanie.url

                // Odnośniki poza naszą witryną oddajemy przeglądarce — w
                // aplikacji ma być ewidencja, a nie cały internet.
                if (url.host != null && !tenSamHost(url)) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, url))
                    } catch (e: Exception) {
                        Toast.makeText(
                            this@MainActivity,
                            R.string.blad_otwarcia,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return true
                }

                return false
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (url.contains("token", true)) {
                    ustawienia.strona = url
                }
            }
        }

        widok.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: GeolocationPermissions.Callback
            ) {
                // Strona prosi o lokalizację. Jeżeli aplikacja ma już
                // uprawnienie systemowe, nie zawracamy kierowcy głowy drugim
                // pytaniem o to samo.
                if (maGps()) {
                    callback.invoke(origin, true, true)
                    return
                }

                czekaNaZgode = callback
                czekaNaZgodeOrigin = origin
                poprosOUprawnienia()
            }
        }
    }

    private fun tenSamHost(url: Uri): Boolean {
        val biezacy = Uri.parse(widok.url ?: ustawienia.strona).host ?: return true

        return url.host.equals(biezacy, true)
    }

    private fun otworz(adres: String) {
        konfiguracja.visibility = View.GONE
        widok.visibility = View.VISIBLE
        ustawienia.strona = adres
        widok.loadUrl(adres)
    }

    /* ---------- ekran konfiguracji ---------- */

    /** Adres witryny wpisany przy budowaniu — podpowiedź w polu linku. */
    private fun domyslnyAdres(): String = getString(R.string.domyslny_adres)

    private fun przygotujKonfiguracje() {
        val pole = findViewById<EditText>(R.id.pole_adres)
        val przycisk = findViewById<Button>(R.id.przycisk_otworz)

        pole.setText(ustawienia.strona.ifEmpty { domyslnyAdres() })

        przycisk.setOnClickListener {
            var adres = pole.text.toString().trim()

            if (adres.isEmpty()) {
                pole.error = getString(R.string.blad_pusty_adres)
                return@setOnClickListener
            }

            if (!adres.startsWith("http://") && !adres.startsWith("https://")) {
                adres = "https://$adres"
            }

            otworz(adres)
        }

        findViewById<TextView>(R.id.wersja).text =
            getString(R.string.wersja_aplikacji, BuildConfig.VERSION_NAME)
    }

    private fun pokazKonfiguracje() {
        konfiguracja.visibility = View.VISIBLE
        widok.visibility = View.GONE
    }

    private fun adresZIntentu(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_VIEW) {
            return null
        }

        return intent.data?.toString()
    }

    /* ---------- uprawnienia ---------- */

    private fun maGps(): Boolean = ContextCompat.checkSelfPermission(
        this, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    /**
     * Uprawnienia bierzemy etapami, bo Android nie pozwala inaczej: najpierw
     * lokalizacja w czasie korzystania, dopiero w osobnym kroku „zawsze".
     * Prośba o oba naraz kończy się cichą odmową dla tła.
     */
    fun poprosOUprawnienia() {
        if (!maGps()) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                KOD_GPS
            )
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), KOD_POWIADOMIENIA)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                KOD_TLO
            )
        }
    }

    override fun onRequestPermissionsResult(
        kod: Int,
        uprawnienia: Array<out String>,
        wyniki: IntArray
    ) {
        super.onRequestPermissionsResult(kod, uprawnienia, wyniki)

        val zgoda = wyniki.isNotEmpty() && wyniki[0] == PackageManager.PERMISSION_GRANTED

        if (kod == KOD_GPS) {
            czekaNaZgode?.invoke(czekaNaZgodeOrigin.orEmpty(), zgoda, zgoda)
            czekaNaZgode = null
            czekaNaZgodeOrigin = null
        }

        if (zgoda && (kod == KOD_GPS || kod == KOD_POWIADOMIENIA)) {
            // Następny etap: powiadomienia, a potem lokalizacja w tle.
            poprosOUprawnienia()
        }

        widok.evaluateJavascript("window.ffNatywneOdswiez && window.ffNatywneOdswiez();", null)
    }

    fun otworzUstawienia() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)
                )
            )
        } catch (e: Exception) {
            Toast.makeText(this, R.string.blad_otwarcia, Toast.LENGTH_SHORT).show()
        }
    }

    /* ---------- jednorazowy pomiar pozycji ---------- */

    /**
     * Pozycja na żądanie strony, pobrana natywnie.
     *
     * Dzięki temu aplikacja działa także wtedy, gdy witryna stoi na HTTP:
     * przeglądarka odmawia wtedy lokalizacji, a usługa systemowa nie ma z tym
     * problemu. Wynik wraca do strony przez `window.ffPozycjaNatywna`.
     */
    fun zmierzPozycje() {
        if (!maGps()) {
            oddajPozycje(null)
            poprosOUprawnienia()
            return
        }

        val zapytanie = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMaxUpdateAgeMillis(30000L)
            .setDurationMillis(15000L)
            .build()

        try {
            LocationServices.getFusedLocationProviderClient(this)
                .getCurrentLocation(zapytanie, null)
                .addOnSuccessListener { oddajPozycje(it) }
                .addOnFailureListener { oddajPozycje(null) }
        } catch (e: SecurityException) {
            oddajPozycje(null)
        }
    }

    private fun oddajPozycje(lokalizacja: android.location.Location?) {
        val json = if (lokalizacja == null) {
            "null"
        } else {
            JSONObject().apply {
                put("lat", lokalizacja.latitude)
                put("lng", lokalizacja.longitude)
                put(
                    "dokladnosc",
                    if (lokalizacja.hasAccuracy()) lokalizacja.accuracy.toDouble() else JSONObject.NULL
                )
            }.toString()
        }

        runOnUiThread {
            widok.evaluateJavascript(
                "window.ffPozycjaNatywna && window.ffPozycjaNatywna($json);",
                null
            )
        }
    }

    companion object {
        private const val KOD_GPS = 10
        private const val KOD_TLO = 11
        private const val KOD_POWIADOMIENIA = 12
    }
}
