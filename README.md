# Storyboard Studio 0.2.0

Aplicación Android nativa para tablets en orientación horizontal, compatible con Samsung Galaxy Tab S6 Lite y S Pen.

## Cambios principales de 0.2.0
- Timeline horizontal con regla de tiempo.
- Duración real por plano, visible en cada bloque.
- Ajuste de duración arrastrando el borde derecho de cada bloque.
- Ajuste fino mediante botones de +/- 0,5 s y cuadro de duración.
- Reordenamiento de planos arrastrando la zona izquierda del bloque.
- Navegación anterior/siguiente y selección directa de planos.
- Pista de audio con forma de onda generada al decodificar el audio.
- Playhead sincronizado con la reproducción y búsqueda tocando la regla o la pista de audio.
- Zoom de timeline y centrado del cabezal.
- Orientación horizontal en ambos sentidos (`sensorLandscape`).

## Compilar
El proyecto utiliza Android Gradle Plugin 8.7.3, Kotlin 2.0.21, JDK 17 y compileSdk 35.

Desde GitHub Actions se puede ejecutar `Build Storyboard Studio APK` y descargar el artefacto `StoryboardStudio-APK`.

## Limitaciones de esta versión
- La forma de onda se calcula al importar el audio. Si un códec del dispositivo no permite decodificar el archivo, el audio puede seguir reproduciéndose, pero la forma de onda podría quedar vacía.
- El proyecto guarda los dibujos exportados y metadatos de duración; todavía no implementa la reapertura completa de un proyecto editable.
- La exportación MP4/animática aún no está incluida.
