# Storyboard Studio 0.4.0

## Interfaz y navegación
- Timeline: pellizco con dos dedos para acercar/alejar, además de botones +/- y centrado.
- Panel de dibujo: un dedo desplaza el lienzo; dos dedos desplazan, amplían/reducen y rotan.
- S Pen: sigue dibujando sobre el lienzo transformado; el dibujo conserva sus coordenadas y no se deforma.
- Botón de restablecer vista del lienzo.
- Botones con áreas táctiles más grandes y controles principales más visuales.

## Sesiones
- Guardado automático de la sesión de trabajo actual.
- Guardar copias internas independientes y abrirlas desde el administrador.
- Crear una sesión nueva guarda primero una copia de la sesión anterior.
- Exportar e importar archivos `.storyboard.json` desde el selector de archivos Android.
- El JSON contiene duración y orden de planos, dibujos, trazos, notas y referencia al audio.
- El audio se referencia mediante URI; no se incrusta dentro del JSON. Si el archivo de audio se mueve o elimina, habrá que volver a seleccionarlo.

## Compilación
- Android Gradle Plugin 8.7.3
- Kotlin 2.0.21
- JDK 17
- compileSdk 35
- minSdk 23
