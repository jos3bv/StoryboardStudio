# Storyboard Studio 0.6.0

## Interfaz
- Tamaño de botones y tipografía de controles reducido aproximadamente a la mitad respecto de 0.5.0.
- Barra de timeline en una sola línea: navegación/reproducción/zoom a la izquierda; tomas y audio a la derecha.
- Se eliminaron las dos filas superiores separadas.

## Pincel
- Trazo suavizado mediante curvas cuadráticas y muestreo de trayectoria.
- Uniones y extremos redondeados con antialiasing.
- Captura de muestras históricas del S Pen para evitar saltos al dibujar rápido.
- Sensibilidad de presión con respuesta curva y variación progresiva del grosor.
- Se conservan tamaño y opacidad configurables.

## Exportación a Documentos
En Android 10 o posterior, PNG y PDF se guardan usando MediaStore en: `Documentos/StoryboardStudio/PNG` y `Documentos/StoryboardStudio/PDF`. No es necesario entrar manualmente a Android/data.
En Android 6–9 se usa el directorio Documents privado de la aplicación como alternativa compatible.

## Compilación
- Android Gradle Plugin 8.7.3
- Kotlin 2.0.21
- JDK 17
- compileSdk 35
- minSdk 23
