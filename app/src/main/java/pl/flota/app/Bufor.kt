package pl.flota.app

import android.content.Context
import android.location.Location
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Bufor punktów trasy.
 *
 * Punkt najpierw ląduje na dysku, dopiero potem rusza na serwer. Powód jest
 * prozaiczny: w trasie zasięg znika, a system może zabić proces w dowolnym
 * momencie. Bufor w pamięci oznaczałby dziurę w ewidencji, a ewidencja
 * przebiegu z dziurą jest bezwartościowa.
 */
class Bufor(context: Context) {

    private val p = context.getSharedPreferences("flota_bufor", Context.MODE_PRIVATE)

    @Synchronized
    fun dodaj(lokalizacja: Location) {
        val punkt = JSONObject().apply {
            put("lat", lokalizacja.latitude)
            put("lng", lokalizacja.longitude)
            put("czas", czasIso(lokalizacja.time))
            if (lokalizacja.hasAccuracy()) put("dokladnosc", lokalizacja.accuracy.toDouble())
            if (lokalizacja.hasSpeed()) put("predkosc", lokalizacja.speed.toDouble())
            if (lokalizacja.hasAltitude()) put("wysokosc", lokalizacja.altitude)
        }

        val tablica = wczytaj()
        tablica.put(punkt)

        // Górne ograniczenie chroni pamięć przy wielogodzinnym braku zasięgu.
        // Przy jednym punkcie na 5 s to z grubsza 14 godzin jazdy.
        if (tablica.length() > LIMIT) {
            val przyciete = JSONArray()
            for (i in (tablica.length() - LIMIT) until tablica.length()) {
                przyciete.put(tablica.get(i))
            }
            zapisz(przyciete)
        } else {
            zapisz(tablica)
        }
    }

    /** Pierwsze [ile] punktów, bez usuwania — zdejmujemy je po udanym zapisie. */
    @Synchronized
    fun paczka(ile: Int): JSONArray {
        val tablica = wczytaj()
        val wynik = JSONArray()

        for (i in 0 until minOf(ile, tablica.length())) {
            wynik.put(tablica.get(i))
        }

        return wynik
    }

    @Synchronized
    fun usunPoczatek(ile: Int) {
        val tablica = wczytaj()
        val wynik = JSONArray()

        for (i in ile until tablica.length()) {
            wynik.put(tablica.get(i))
        }

        zapisz(wynik)
    }

    @Synchronized
    fun ile(): Int = wczytaj().length()

    @Synchronized
    fun wyczysc() {
        p.edit().remove(KLUCZ).apply()
    }

    private fun wczytaj(): JSONArray = try {
        JSONArray(p.getString(KLUCZ, "[]"))
    } catch (e: Exception) {
        JSONArray()
    }

    private fun zapisz(tablica: JSONArray) {
        p.edit().putString(KLUCZ, tablica.toString()).apply()
    }

    companion object {
        private const val KLUCZ = "punkty"
        private const val LIMIT = 10000

        /**
         * Czas w UTC ze znacznikiem strefy. Serwer sam przelicza go na czas
         * lokalny — gdybyśmy wysłali czas bez strefy, godziny w ewidencji
         * rozjechałyby się o przesunięcie strefowe.
         */
        fun czasIso(ms: Long): String {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            format.timeZone = TimeZone.getTimeZone("UTC")
            return format.format(Date(ms))
        }
    }
}
