package pl.flota.app

import android.content.Context
import android.content.SharedPreferences

/**
 * Trwałe ustawienia aplikacji.
 *
 * Trzymamy tu adres witryny i token z linku, bo kierowca nie powinien ich
 * wklejać za każdym razem. Token to jedyne poświadczenie — nie ma konta
 * w WordPressie, więc jego utrata oznacza tylko konieczność wysłania
 * nowego linku, a nie dostęp do panelu.
 */
class Ustawienia(context: Context) {

    private val p: SharedPreferences =
        context.getSharedPreferences("flota", Context.MODE_PRIVATE)

    var adres: String
        get() = p.getString(KLUCZ_ADRES, "").orEmpty()
        set(v) = p.edit().putString(KLUCZ_ADRES, v.trimEnd('/')).apply()

    /** Pełny adres ostatnio otwartej strony ewidencji (z tokenem w zapytaniu). */
    var strona: String
        get() = p.getString(KLUCZ_STRONA, "").orEmpty()
        set(v) = p.edit().putString(KLUCZ_STRONA, v).apply()

    var token: String
        get() = p.getString(KLUCZ_TOKEN, "").orEmpty()
        set(v) = p.edit().putString(KLUCZ_TOKEN, v).apply()

    /** Adres bazowy REST, np. https://domena.pl/wp-json/flota/v1 */
    var rest: String
        get() = p.getString(KLUCZ_REST, "").orEmpty()
        set(v) = p.edit().putString(KLUCZ_REST, v.trimEnd('/')).apply()

    /** Trasa, która właśnie się nagrywa. 0 = brak. */
    var podrozId: Long
        get() = p.getLong(KLUCZ_PODROZ, 0L)
        set(v) = p.edit().putLong(KLUCZ_PODROZ, v).apply()

    /** Ile sekund bez ruchu liczymy jako postój. */
    var postojSekundy: Int
        get() = p.getInt(KLUCZ_POSTOJ, 60)
        set(v) = p.edit().putInt(KLUCZ_POSTOJ, v).apply()

    fun maKonfiguracje(): Boolean = rest.isNotEmpty() && token.isNotEmpty()

    /**
     * Zapamiętanie kontekstu przekazanego przez stronę. Strona zna token
     * i adres REST, więc nie zgadujemy ich z adresu URL.
     */
    fun zapiszKontekst(rest: String, token: String, strona: String) {
        p.edit()
            .putString(KLUCZ_REST, rest.trimEnd('/'))
            .putString(KLUCZ_TOKEN, token)
            .putString(KLUCZ_STRONA, strona)
            .apply()
    }

    companion object {
        private const val KLUCZ_ADRES = "adres"
        private const val KLUCZ_STRONA = "strona"
        private const val KLUCZ_TOKEN = "token"
        private const val KLUCZ_REST = "rest"
        private const val KLUCZ_PODROZ = "podroz"
        private const val KLUCZ_POSTOJ = "postoj"
    }
}
