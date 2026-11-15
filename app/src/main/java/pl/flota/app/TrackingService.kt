package pl.flota.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Nagrywanie trasy w tle.
 *
 * To jedyny powód, dla którego ta aplikacja istnieje. Przeglądarka przestaje
 * dostawać pozycje po zgaszeniu ekranu i trasa urywa się w połowie drogi.
 * Usługa pierwszego planu takiego ograniczenia nie ma: system pokazuje
 * powiadomienie i pozwala zbierać pozycje, dopóki kierowca sam nie zakończy
 * przejazdu.
 *
 * Wykrywanie postojów powtarza logikę ze strony — kotwica plus promień, nie
 * porównanie z poprzednim punktem. Porównywanie sąsiednich punktów przegrywa
 * z dryfem GPS na parkingu: licznik czasu zeruje się w nieskończoność i postój
 * nigdy nie zostaje zgłoszony.
 */
class TrackingService : Service() {

    private lateinit var ustawienia: Ustawienia
    private lateinit var bufor: Bufor
    private lateinit var lokalizacje: FusedLocationProviderClient

    private var wykonawca: ScheduledExecutorService? = null
    private var blokada: PowerManager.WakeLock? = null

    private var kotwica: Location? = null
    private var kotwicaCzas: Long = 0L
    private var postojZgloszony = false

    private val oddzwonienie = object : LocationCallback() {
        override fun onLocationResult(wynik: LocationResult) {
            wynik.locations.forEach { przyjmijPozycje(it) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ustawienia = Ustawienia(this)
        bufor = Bufor(this)
        lokalizacje = LocationServices.getFusedLocationProviderClient(this)
        kanal()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            AKCJA_STOP -> {
                zakoncz()
                return START_NOT_STICKY
            }
        }

        val podroz = intent?.getLongExtra(EXTRA_PODROZ, 0L) ?: 0L

        if (podroz > 0L) {
            ustawienia.podrozId = podroz
        }

        val sekundy = intent?.getIntExtra(EXTRA_POSTOJ, 0) ?: 0

        if (sekundy > 0) {
            ustawienia.postojSekundy = sekundy
        }

        if (ustawienia.podrozId <= 0L || !ustawienia.maKonfiguracje()) {
            // Bez trasy i bez tokenu nie ma czego nagrywać.
            stopSelf()
            return START_NOT_STICKY
        }

        ServiceCompat.startForeground(
            this,
            ID_POWIADOMIENIA,
            powiadomienie(getString(R.string.powiadomienie_start)),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                0
            }
        )

        nagrywa = true
        trzymajProcesor()
        subskrybuj()
        uruchomWysylanie()

        // START_STICKY: gdy system ubije proces przy braku pamięci, usługa
        // wróci i dokończy nagrywanie — trasa i token siedzą w ustawieniach.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        nagrywa = false
        odsubskrybuj()

        // shutdown, nie shutdownNow: trwający zapis punktów ma się dokończyć,
        // a nie zostać przerwany w połowie żądania.
        wykonawca?.shutdown()
        wykonawca = null
        zwolnijProcesor()
        super.onDestroy()
    }

    /* ---------- pozycje ---------- */

    private fun subskrybuj() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf()
            return
        }

        val zapytanie = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L)
            .setMinUpdateIntervalMillis(3000L)
            .setMinUpdateDistanceMeters(10f)
            .setWaitForAccurateLocation(false)
            .build()

        try {
            lokalizacje.requestLocationUpdates(zapytanie, oddzwonienie, mainLooper)
        } catch (e: SecurityException) {
            stopSelf()
        }
    }

    private fun odsubskrybuj() {
        try {
            lokalizacje.removeLocationUpdates(oddzwonienie)
        } catch (e: Exception) {
            /* Usługa i tak kończy pracę. */
        }
    }

    private fun przyjmijPozycje(lokalizacja: Location) {
        // Pozycja o dokładności kilkuset metrów dorzuca kilometry z niczego.
        // Serwer też ją odrzuci, ale po co ją wysyłać.
        if (lokalizacja.hasAccuracy() && lokalizacja.accuracy > 100f) {
            return
        }

        bufor.dodaj(lokalizacja)
        ostatniaPozycja = lokalizacja
        wykryjPostoj(lokalizacja)
        odswiezPowiadomienie()
    }

    private fun wykryjPostoj(lokalizacja: Location) {
        val prog = ustawienia.postojSekundy.coerceAtLeast(30) * 1000L
        val obecna = kotwica

        if (obecna == null) {
            przestawKotwice(lokalizacja)
            return
        }

        if (obecna.distanceTo(lokalizacja) > PROMIEN_KOTWICY) {
            przestawKotwice(lokalizacja)
            return
        }

        if (postojZgloszony) {
            return
        }

        val trwa = lokalizacja.time - kotwicaCzas

        if (trwa >= prog) {
            postojZgloszony = true
            val sekundy = (trwa / 1000L).toInt()
            wykonawca?.execute { zglosPostoj(obecna, sekundy) }
        }
    }

    private fun przestawKotwice(lokalizacja: Location) {
        kotwica = lokalizacja
        kotwicaCzas = lokalizacja.time
        postojZgloszony = false
    }

    /* ---------- wysyłanie ---------- */

    private fun uruchomWysylanie() {
        if (wykonawca != null) {
            return
        }

        wykonawca = Executors.newSingleThreadScheduledExecutor().also {
            it.scheduleWithFixedDelay({ wyslijBufor() }, 10, 20, TimeUnit.SECONDS)
        }
    }

    private fun api(): Api = Api(ustawienia.rest, ustawienia.token)

    private fun wyslijBufor() {
        val podroz = ustawienia.podrozId

        if (podroz <= 0L) {
            return
        }

        val paczka = bufor.paczka(100)

        if (paczka.length() == 0) {
            return
        }

        val dane = JSONObject().apply {
            put("podroz_id", podroz)
            put("punkty", paczka)
        }

        val odp = api().post("/trasa/punkty", dane)

        if (!odp.ok) {
            // Punkty zostają w buforze. Jedyny wyjątek to unieważniony token:
            // dalsze próby nic nie dadzą, więc kończymy nagrywanie.
            if (odp.kod == 401) {
                bladTokenu = true
                zakoncz()
            }
            return
        }

        val json = odp.json()
        bufor.usunPoczatek(paczka.length())
        wyslanePunkty += json.optInt("zapisane", 0)
        dystans = json.optDouble("dystans", dystans)
        odswiezPowiadomienie()
    }

    private fun zglosPostoj(punkt: Location, sekundy: Int) {
        val podroz = ustawienia.podrozId

        if (podroz <= 0L) {
            return
        }

        // Punkty trasy wysyłamy przed postojem, żeby serwer rozpoznawał
        // miejsce mając pełny ogon trasy, a nie urwany.
        wyslijBufor()

        val dane = JSONObject().apply {
            put("podroz_id", podroz)
            put("lat", punkt.latitude)
            put("lng", punkt.longitude)
            put("czas_sekundy", sekundy)
            put("poczatek", Bufor.czasIso(punkt.time - sekundy * 1000L))
        }

        val odp = api().post("/trasa/postoj", dane)

        if (!odp.ok) {
            // Nieudane zgłoszenie odblokowujemy, żeby kolejna próba miała
            // szansę — inaczej postój przepadłby na zawsze.
            postojZgloszony = false
            return
        }

        val json = odp.json()
        val miejsce = json.optJSONObject("miejsce")

        if (miejsce != null) {
            dopiszRozpoznany(miejsce.optString("nazwa"), sekundy)
            return
        }

        // Miejsce nieznane — zapisujemy je do kolejki, a ekran aplikacji
        // zapyta o nazwę, gdy kierowca znów na niego spojrzy.
        dopiszDoNazwania(
            JSONObject().apply {
                put("postoj_id", json.optInt("postoj_id"))
                put("lat", punkt.latitude)
                put("lng", punkt.longitude)
                put("sekundy", sekundy)
                put("w_poblizu", json.optJSONArray("w_poblizu") ?: JSONArray())
            }
        )
    }

    /* ---------- powiadomienie ---------- */

    private fun kanal() {
        val menedzer = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val kanal = NotificationChannel(
            KANAL,
            getString(R.string.kanal_nazwa),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.kanal_opis)
            setShowBadge(false)
        }

        menedzer.createNotificationChannel(kanal)
    }

    private fun powiadomienie(tresc: String): android.app.Notification {
        val otworz = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, KANAL)
            .setContentTitle(getString(R.string.powiadomienie_tytul))
            .setContentText(tresc)
            .setSmallIcon(R.drawable.ic_stat_trasa)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(otworz)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun odswiezPowiadomienie() {
        val menedzer = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val tresc = getString(
            R.string.powiadomienie_stan,
            String.format("%.1f", dystans).replace('.', ','),
            bufor.ile()
        )

        menedzer.notify(ID_POWIADOMIENIA, powiadomienie(tresc))
    }

    /* ---------- procesor ---------- */

    private fun trzymajProcesor() {
        if (blokada != null) {
            return
        }

        val menedzer = getSystemService(Context.POWER_SERVICE) as PowerManager

        blokada = menedzer.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "flota:trasa").apply {
            setReferenceCounted(false)
            // Limit czasu to zabezpieczenie: gdyby usługa padła bez
            // zwolnienia blokady, bateria nie zniknie przez całą dobę.
            acquire(12 * 60 * 60 * 1000L)
        }
    }

    private fun zwolnijProcesor() {
        try {
            blokada?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            /* Zwolnienie już zwolnionej blokady nie jest problemem. */
        }
        blokada = null
    }

    private fun zakoncz() {
        nagrywa = false
        odsubskrybuj()

        // Ostatni zapis idzie własnym wątkiem, nie planistą usługi. Usługa
        // zaraz zniknie i jej planista zostanie zamknięty, a końcówka trasy
        // musi dojechać do bazy — bez niej w ewidencji zostaje urwany
        // odcinek, czego już nikt później nie odtworzy.
        if (!bladTokenu && bufor.ile() > 0) {
            val kopia = Api(ustawienia.rest, ustawienia.token)
            val podroz = ustawienia.podrozId
            val magazyn = bufor

            Thread {
                var proby = 0

                while (magazyn.ile() > 0 && proby < 5) {
                    val paczka = magazyn.paczka(100)

                    val odp = kopia.post(
                        "/trasa/punkty",
                        JSONObject().apply {
                            put("podroz_id", podroz)
                            put("punkty", paczka)
                        }
                    )

                    if (!odp.ok) {
                        break
                    }

                    magazyn.usunPoczatek(paczka.length())
                    wyslanePunkty += odp.json().optInt("zapisane", 0)
                    dystans = odp.json().optDouble("dystans", dystans)
                    ++proby
                }
            }.start()
        }

        // Trasa zamknięta: zwalniamy blokadę aktualizacji aplikacji
        // i nie pozwalamy usłudze wznowić nagrywania po restarcie.
        ustawienia.podrozId = 0L

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val AKCJA_START = "pl.flota.app.START"
        const val AKCJA_STOP = "pl.flota.app.STOP"
        const val EXTRA_PODROZ = "podroz"
        const val EXTRA_POSTOJ = "postoj"

        private const val KANAL = "trasa"
        private const val ID_POWIADOMIENIA = 1001
        private const val PROMIEN_KOTWICY = 60f

        @Volatile
        var nagrywa: Boolean = false

        @Volatile
        var bladTokenu: Boolean = false

        @Volatile
        var dystans: Double = 0.0

        @Volatile
        var wyslanePunkty: Int = 0

        @Volatile
        var ostatniaPozycja: Location? = null

        /** Postoje bez nazwy, czekające na pytanie do kierowcy. */
        private val doNazwania = java.util.concurrent.ConcurrentLinkedQueue<JSONObject>()

        /** Postoje rozpoznane po drodze — tylko do pokazania na liście. */
        private val rozpoznane = java.util.concurrent.ConcurrentLinkedQueue<JSONObject>()

        fun dopiszDoNazwania(postoj: JSONObject) {
            doNazwania.add(postoj)
        }

        fun dopiszRozpoznany(nazwa: String, sekundy: Int) {
            rozpoznane.add(JSONObject().apply {
                put("nazwa", nazwa)
                put("sekundy", sekundy)
            })
        }

        /** Zdjęcie kolejek — strona odbiera każdy postój dokładnie raz. */
        fun pobierzPostoje(): JSONObject {
            val bez = JSONArray()
            val znane = JSONArray()

            while (true) {
                val p = doNazwania.poll() ?: break
                bez.put(p)
            }

            while (true) {
                val p = rozpoznane.poll() ?: break
                znane.put(p)
            }

            return JSONObject().apply {
                put("do_nazwania", bez)
                put("rozpoznane", znane)
            }
        }

        fun zeruj() {
            dystans = 0.0
            wyslanePunkty = 0
            bladTokenu = false
            doNazwania.clear()
            rozpoznane.clear()
        }

        fun start(context: Context, podrozId: Long, postojSekundy: Int) {
            val intent = Intent(context, TrackingService::class.java).apply {
                action = AKCJA_START
                putExtra(EXTRA_PODROZ, podrozId)
                putExtra(EXTRA_POSTOJ, postojSekundy)
            }

            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, TrackingService::class.java).apply {
                action = AKCJA_STOP
            }

            try {
                context.startService(intent)
            } catch (e: Exception) {
                /* Usługa mogła już nie działać. */
            }
        }
    }
}
