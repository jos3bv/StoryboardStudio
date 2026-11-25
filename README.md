# BV'Storyboarder 1.0.0 — código fuente

## Motor de dibujo

Esta versión integra **AndroidX Ink 1.0.0** como motor de entrada y renderizado del lápiz.

- Captura de stylus mediante `InProgressStrokesView`.
- Previsualización de trazo en curso (wet ink) y entrega directa al renderizador de contenido confirmado (dry ink).
- Presión con `StockBrushes.pressurePen()`; modo sin presión con `StockBrushes.marker()`.
- Opacidad aplicada desde el color ARGB del pincel.
- Goma rasterizada mediante una máscara temporal y composición `DST_OUT` sobre el bitmap del plano.
- Lazo por píxeles sobre el contenido unificado: recorte libre, traslado, escala, rotación, aceptar y cancelar.
- El escaneo de píxeles del lazo usa memoria auxiliar de una fila, no un `IntArray` del tamaño de toda la selección.

## Documento

- Un bitmap RGBA `1920×1080` por plano.
- Dibujos e imágenes importadas se integran en ese bitmap.
- El lienzo y las áreas borradas son transparentes; el fondo del espacio de trabajo se ve a través del alfa. Los PNG conservan esa transparencia.
- Notas del plano se mantienen como metadatos independientes.
- Historial global de 10 pasos, limitado al contenido del lienzo y a operaciones de planos.
- Los archivos de sesión de esta versión almacenan el PNG de cada bitmap en el JSON.

## Sesiones y compatibilidad

La versión 1.0.0 **no migra ni abre sesiones antiguas** con estructuras de trazos vectoriales o imágenes independientes. Solo lee el formato unificado de bitmap de esta versión.

Carpeta de trabajo: `Documentos/BVstoryboarder`.

## Requisitos

- Android Studio con Android Gradle Plugin 8.7.3.
- JDK 17.
- Android SDK 35.
- `minSdk = 26`.
- Dependencias AndroidX Ink fijadas en `1.0.0`.

## Compilación

Abre el proyecto en Android Studio y ejecuta `:app:assembleDebug`. También se incluye un workflow de GitHub Actions (`.github/workflows/android.yml`) que compila el APK debug y lo publica como artefacto al hacer push o ejecutar manualmente el workflow.

**Estado de validación de esta entrega:** se revisaron las dependencias declaradas, la estructura del proyecto, los recursos XML y la integridad del ZIP. No se ejecutó Gradle ni se generó un APK en este entorno porque no hay Android SDK, Gradle ni Gradle Wrapper disponibles. El funcionamiento del stylus, la presión, la goma, el lazo y el traspaso wet/dry requiere compilación y prueba en dispositivo, especialmente en la Galaxy Tab S6 Lite.
