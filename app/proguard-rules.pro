# Most do JavaScriptu znika przy skracaniu nazw — strona szukałaby metod,
# których już nie ma. Zachowujemy je jawnie, nawet gdy minifikacja jest
# wyłączona, żeby włączenie jej kiedyś nie zepsuło aplikacji po cichu.
-keepclassmembers class pl.flota.app.MostJS {
    public *;
}
-keep class pl.flota.app.MostJS { *; }
