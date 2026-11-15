package pl.flota.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimalny klient REST wtyczki „Flota firmowa".
 *
 * Bez bibliotek zewnętrznych — HttpURLConnection wystarcza, a każda dodana
 * zależność to kolejna rzecz, która może nie zbudować się w CI.
 *
 * Wszystkie metody są blokujące i muszą być wołane z wątku roboczego.
 */
class Api(private val rest: String, private val token: String) {

    class Odpowiedz(val kod: Int, val tresc: String) {
        val ok: Boolean get() = kod in 200..299

        fun json(): JSONObject = try {
            JSONObject(tresc)
        } catch (e: Exception) {
            JSONObject()
        }
    }

    fun post(sciezka: String, dane: JSONObject): Odpowiedz = zapytanie(sciezka, "POST", dane)

    fun get(sciezka: String): Odpowiedz = zapytanie(sciezka, "GET", null)

    private fun zapytanie(sciezka: String, metoda: String, dane: JSONObject?): Odpowiedz {
        var polaczenie: HttpURLConnection? = null

        return try {
            polaczenie = (URL(rest + sciezka).openConnection() as HttpURLConnection).apply {
                requestMethod = metoda
                connectTimeout = 15000
                readTimeout = 20000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-FF-Token", token)
                setRequestProperty("User-Agent", "FlotaFirmowa-Android")
            }

            if (dane != null) {
                polaczenie.doOutput = true
                polaczenie.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                polaczenie.outputStream.use { it.write(dane.toString().toByteArray(Charsets.UTF_8)) }
            }

            val kod = polaczenie.responseCode
            val strumien = if (kod in 200..299) polaczenie.inputStream else polaczenie.errorStream
            val tresc = strumien?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

            Odpowiedz(kod, tresc)
        } catch (e: Exception) {
            // Brak zasięgu nie jest błędem aplikacji — punkty zostaną
            // w buforze i pojadą przy następnej próbie.
            Odpowiedz(0, e.message.orEmpty())
        } finally {
            polaczenie?.disconnect()
        }
    }
}
