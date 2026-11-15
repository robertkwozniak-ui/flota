package pl.flota.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.webkit.JavascriptInterface
import androidx.core.content.ContextCompat
import org.json.JSONObject

/**
 * Most między stroną ewidencji a warstwą natywną.
 *
 * Strona pozostaje tą samą stroną, którą kierowca otwiera w przeglądarce —
 * aplikacja tylko podstawia pod nią nagrywanie w tle. Jeżeli tego mostu nie ma
 * (zwykła przeglądarka), strona działa po staremu na `navigator.geolocation`.
 *
 * Uwaga: metody oznaczone @JavascriptInterface wołane są z wątku WebView,
 * nie z głównego. Wszystko, co dotyka interfejsu, trafia na główny wątek.
 */
class MostJS(private val activity: MainActivity) {

    @JavascriptInterface
    fun dostepny(): Boolean = true

    @JavascriptInterface
    fun wersja(): String = BuildConfig.VERSION_NAME

    /**
     * Strona podaje własny adres REST i token — nie zgadujemy ich z adresu URL,
     * bo instalacja WordPressa może stać w podkatalogu albo mieć przepisane
     * adresy.
     */
    @JavascriptInterface
    fun konfiguruj(rest: String, token: String, strona: String) {
        if (rest.isBlank() || token.isBlank()) {
            return
        }

        Ustawienia(activity).zapiszKontekst(rest, token, strona)
    }

    @JavascriptInterface
    fun uprawnienia(): String {
        val gps = ContextCompat.checkSelfPermission(
            activity, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val tlo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                activity, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val powiadomienia = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                activity, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        return JSONObject().apply {
            put("gps", gps)
            put("tlo", tlo)
            put("powiadomienia", powiadomienia)
        }.toString()
    }

    /** Prośba o uprawnienia. Okno pokazuje system, więc musi iść na główny wątek. */
    @JavascriptInterface
    fun popros() {
        activity.runOnUiThread { activity.poprosOUprawnienia() }
    }

    /** Ekran ustawień aplikacji — dla sytuacji „odmówiono na stałe". */
    @JavascriptInterface
    fun ustawieniaSystemu() {
        activity.runOnUiThread { activity.otworzUstawienia() }
    }

    @JavascriptInterface
    fun start(podrozId: String, postojSekundy: Int): Boolean {
        val id = podrozId.toLongOrNull() ?: return false

        if (id <= 0L) {
            return false
        }

        val maGps = ContextCompat.checkSelfPermission(
            activity, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!maGps) {
            activity.runOnUiThread { activity.poprosOUprawnienia() }
            return false
        }

        TrackingService.zeruj()
        activity.runOnUiThread { TrackingService.start(activity, id, postojSekundy) }

        return true
    }

    @JavascriptInterface
    fun stop() {
        activity.runOnUiThread { TrackingService.stop(activity) }
    }

    @JavascriptInterface
    fun stan(): String {
        val bufor = Bufor(activity)

        return JSONObject().apply {
            put("nagrywa", TrackingService.nagrywa)
            put("dystans", TrackingService.dystans)
            put("punkty", TrackingService.wyslanePunkty)
            put("bufor", bufor.ile())
            put("blad_tokenu", TrackingService.bladTokenu)
        }.toString()
    }

    /**
     * Postoje wykryte w tle. Każdy wydawany jest raz — strona zapyta o nazwę
     * tych nierozpoznanych, gdy kierowca znów na nią spojrzy.
     */
    @JavascriptInterface
    fun postoje(): String = TrackingService.pobierzPostoje().toString()

    /**
     * Jednorazowy pomiar pozycji przez usługę systemową. Wynik wraca
     * asynchronicznie do `window.ffPozycjaNatywna`.
     *
     * Natywny pomiar działa także na witrynie po HTTP, gdzie przeglądarka
     * odmawia dostępu do lokalizacji.
     */
    @JavascriptInterface
    fun zmierz() {
        activity.runOnUiThread { activity.zmierzPozycje() }
    }

    /** Ostatnia znana pozycja z usługi — przydaje się przy zamykaniu trasy. */
    @JavascriptInterface
    fun pozycja(): String {
        val p = TrackingService.ostatniaPozycja ?: return "null"

        return JSONObject().apply {
            put("lat", p.latitude)
            put("lng", p.longitude)
            put("dokladnosc", if (p.hasAccuracy()) p.accuracy.toDouble() else JSONObject.NULL)
        }.toString()
    }

    companion object {
        const val NAZWA = "FlotaNatywna"
    }
}
