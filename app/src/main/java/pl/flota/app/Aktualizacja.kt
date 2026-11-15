package pl.flota.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Samodzielna aktualizacja aplikacji.
 *
 * Aplikacja nie jest w Google Play, więc nikt jej za nas nie zaktualizuje.
 * Przy każdym otwarciu sprawdzamy najnowsze wydanie na GitHubie; jeśli jest
 * nowsze, pobieramy plik, weryfikujemy sumę kontrolną i oddajemy go
 * instalatorowi systemu.
 *
 * Android pozwala aplikacji zaktualizować samą siebie bez pytania dopiero
 * od wersji 12 i dopiero wtedy, gdy poprzednią aktualizację też instalowała
 * ona sama. W praktyce: pierwsza aktualizacja wymaga jednego dotknięcia
 * „Zaktualizuj", kolejne przechodzą po cichu. Na Androidzie 8–11 pytanie
 * pojawia się za każdym razem — tego ograniczenia nie da się obejść.
 *
 * Nigdy nie instalujemy w trakcie przejazdu: instalacja ubija proces,
 * a razem z nim nagrywanie trasy.
 */
object Aktualizacja {

    private const val ODSTEP_MS = 3 * 60 * 60 * 1000L
    private const val KLUCZ_SPRAWDZONO = "sprawdzono"
    private const val KLUCZ_NIEUDANY = "nieudany_kod"
    private const val KLUCZ_NIEUDANY_CZAS = "nieudany_czas"
    const val KANAL = "aktualizacje"

    private val trwa = AtomicBoolean(false)

    private fun prefs(context: Context) =
        context.getSharedPreferences("flota_aktualizacja", Context.MODE_PRIVATE)

    fun sprawdz(context: Context) {
        val baza = BuildConfig.ADRES_AKTUALIZACJI

        // Wersja testowa ma inny podpis niż wydania — system i tak
        // odrzuciłby aktualizację, więc nawet nie próbujemy.
        if (baza.isBlank() || BuildConfig.DEBUG) {
            return
        }

        if (!trwa.compareAndSet(false, true)) {
            return
        }

        val app = context.applicationContext

        Thread {
            try {
                przebieg(app, baza.trimEnd('/'))
            } catch (e: Exception) {
                /* Brak sieci albo GitHub nie odpowiada — spróbujemy później. */
            } finally {
                trwa.set(false)
            }
        }.start()
    }

    private fun przebieg(context: Context, baza: String) {
        val p = prefs(context)
        val katalog = File(context.cacheDir, "aktualizacja").apply { mkdirs() }
        val opis = File(katalog, "wersja.json")
        val teraz = System.currentTimeMillis()

        // Do sieci sięgamy najwyżej raz na kilka godzin; pobraną wcześniej
        // paczkę, która czekała na koniec przejazdu, instalujemy od razu.
        if (teraz - p.getLong(KLUCZ_SPRAWDZONO, 0L) > ODSTEP_MS || !opis.exists()) {
            val tresc = pobierzTekst("$baza/wersja.json") ?: return
            opis.writeText(tresc)
            p.edit().putLong(KLUCZ_SPRAWDZONO, teraz).apply()
        }

        val dane = JSONObject(opis.readText())
        val kod = dane.optInt("kod", 0)
        val suma = dane.optString("sha256")
        val nazwaPliku = dane.optString("plik", "flota.apk")

        if (kod <= BuildConfig.VERSION_CODE) {
            katalog.listFiles()?.forEach { if (it.name.endsWith(".apk")) it.delete() }
            return
        }

        // Paczka, która raz się nie zainstalowała, dostaje dobę przerwy —
        // inaczej każde otwarcie aplikacji kończyłoby się tym samym błędem.
        if (p.getInt(KLUCZ_NIEUDANY, 0) == kod &&
            teraz - p.getLong(KLUCZ_NIEUDANY_CZAS, 0L) < 24 * 60 * 60 * 1000L
        ) {
            return
        }

        val paczka = File(katalog, "flota-$kod.apk")

        if (!paczka.exists() || !zgodnaSuma(paczka, suma)) {
            katalog.listFiles()?.forEach { if (it.name.endsWith(".apk")) it.delete() }

            if (!pobierzPlik("$baza/$nazwaPliku", paczka) || !zgodnaSuma(paczka, suma)) {
                paczka.delete()
                return
            }
        }

        if (TrackingService.nagrywa || Ustawienia(context).podrozId > 0L) {
            return
        }

        instaluj(context, paczka, kod)
    }

    private fun instaluj(context: Context, paczka: File, kod: Int) {
        val instalator = context.packageManager.packageInstaller

        val parametry = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        ).apply {
            setAppPackageName(context.packageName)
            setSize(paczka.length())

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }

        val id = instalator.createSession(parametry)
        val sesja = instalator.openSession(id)

        try {
            sesja.openWrite("flota", 0, paczka.length()).use { wyjscie ->
                paczka.inputStream().use { it.copyTo(wyjscie) }
                sesja.fsync(wyjscie)
            }

            val flagi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }

            val wynik = PendingIntent.getBroadcast(
                context,
                id,
                Intent(context, InstalacjaReceiver::class.java).putExtra("kod", kod),
                flagi
            )

            sesja.commit(wynik.intentSender)
        } catch (e: Exception) {
            sesja.abandon()
            oznaczNieudana(context, kod)
        } finally {
            sesja.close()
        }
    }

    fun oznaczNieudana(context: Context, kod: Int) {
        prefs(context).edit()
            .putInt(KLUCZ_NIEUDANY, kod)
            .putLong(KLUCZ_NIEUDANY_CZAS, System.currentTimeMillis())
            .apply()
    }

    /* ---------- sieć ---------- */

    private fun polacz(adres: String): HttpURLConnection {
        var url = adres

        // GitHub przekierowuje do innego hosta; przekierowania prowadzimy
        // sami i nie pozwalamy na zejście z HTTPS.
        repeat(6) {
            val polaczenie = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15000
                readTimeout = 30000
                setRequestProperty("User-Agent", "FlotaFirmowa-Android")
            }

            if (polaczenie.responseCode in 301..308) {
                val dalej = polaczenie.getHeaderField("Location")
                polaczenie.disconnect()

                if (dalej == null || !dalej.startsWith("https://")) {
                    throw IllegalStateException("przekierowanie")
                }

                url = dalej
            } else {
                return polaczenie
            }
        }

        throw IllegalStateException("za dużo przekierowań")
    }

    private fun pobierzTekst(adres: String): String? {
        val polaczenie = polacz(adres)

        return try {
            if (polaczenie.responseCode != 200) {
                null
            } else {
                polaczenie.inputStream.bufferedReader().use { it.readText() }
            }
        } finally {
            polaczenie.disconnect()
        }
    }

    private fun pobierzPlik(adres: String, cel: File): Boolean {
        val polaczenie = polacz(adres)

        return try {
            if (polaczenie.responseCode != 200) {
                false
            } else {
                polaczenie.inputStream.use { wejscie ->
                    cel.outputStream().use { wejscie.copyTo(it) }
                }
                true
            }
        } finally {
            polaczenie.disconnect()
        }
    }

    private fun zgodnaSuma(plik: File, oczekiwana: String): Boolean {
        if (oczekiwana.isBlank()) {
            return false
        }

        val skrot = MessageDigest.getInstance("SHA-256")

        plik.inputStream().use { wejscie ->
            val bufor = ByteArray(64 * 1024)

            while (true) {
                val ile = wejscie.read(bufor)
                if (ile < 0) break
                skrot.update(bufor, 0, ile)
            }
        }

        val wynik = skrot.digest().joinToString("") { "%02x".format(it) }

        return wynik.equals(oczekiwana, ignoreCase = true)
    }
}

/**
 * Odpowiedź instalatora systemu.
 *
 * Gdy system chce potwierdzenia, przysyła gotowe okno — wystarczy je
 * pokazać. Dzieje się to tylko przy otwartej aplikacji, bo aktualizację
 * uruchamiamy z jej ekranu.
 */
class InstalacjaReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val okno = IntentCompat.getParcelableExtra(
                    intent, Intent.EXTRA_INTENT, Intent::class.java
                ) ?: return

                try {
                    context.startActivity(okno.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    /* Aplikacja zeszła z ekranu — zapytamy przy następnym otwarciu. */
                }
            }

            PackageInstaller.STATUS_SUCCESS -> Unit

            else -> Aktualizacja.oznaczNieudana(context, intent.getIntExtra("kod", 0))
        }
    }
}

/**
 * Po cichej aktualizacji aplikacja po prostu znika z ekranu, bo system
 * ubija stary proces. Powiadomienie mówi, co się stało, i otwiera ją
 * z powrotem jednym dotknięciem.
 */
class PoAktualizacjiReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        val menedzer = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        menedzer.createNotificationChannel(
            NotificationChannel(
                Aktualizacja.KANAL,
                context.getString(R.string.kanal_aktualizacje),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )

        val otworz = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val powiadomienie = NotificationCompat.Builder(context, Aktualizacja.KANAL)
            .setSmallIcon(R.drawable.ic_stat_trasa)
            .setContentTitle(context.getString(R.string.aktualizacja_tytul))
            .setContentText(
                context.getString(R.string.aktualizacja_tresc, BuildConfig.VERSION_NAME)
            )
            .setAutoCancel(true)
            .setContentIntent(otworz)
            .build()

        try {
            menedzer.notify(2001, powiadomienie)
        } catch (e: SecurityException) {
            /* Brak zgody na powiadomienia — aktualizacja i tak się udała. */
        }
    }
}
