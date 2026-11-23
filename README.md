# Storyboard Studio — prototipo Android 0.1.0

Proyecto inicial Android para Samsung Galaxy Tab S6 Lite y S Pen. Incluye un flujo de compilación automática en GitHub Actions para generar el APK sin instalar Android Studio en el PC.

## Funciones incluidas en esta primera base

- Interfaz horizontal.
- Lienzo 16:9 (1920 × 1080) sobre fondo blanco.
- Dibujo con S Pen y lectura de presión cuando el dispositivo la entrega.
- Dibujo con dedo como alternativa.
- Pincel, selector de tres colores, borrador, tamaño de pincel, deshacer y limpiar con confirmación.
- Viñetas independientes, selección, creación y duplicación.
- Anotaciones de texto simples.
- Importación y reproducción/pausa de audio mediante selector de archivos Android.
- Guardado de imágenes PNG por viñeta y exportación PDF multipágina.

## Generar el APK automáticamente (sin Android Studio)

1. Entra en https://github.com/ e inicia sesión o crea una cuenta.
2. Crea un repositorio nuevo llamado `StoryboardStudio`. Puede ser **Private**.
3. En el repositorio, pulsa **Add file → Upload files**.
4. Descomprime el ZIP de este proyecto en tu PC. Sube el contenido de la carpeta `StoryboardStudio` (incluida la carpeta oculta `.github`) a la raíz del repositorio. No subas una carpeta `StoryboardStudio` dentro de otra.
5. Pulsa **Commit changes**.
6. Abre la pestaña **Actions** del repositorio. Si GitHub pregunta si deseas habilitar workflows, confirma.
7. Selecciona **Build Storyboard Studio APK** en la lista de workflows y pulsa **Run workflow** si no comenzó automáticamente al subir los archivos.
8. Espera a que el trabajo termine con un visto bueno verde. Abre esa ejecución y baja hasta **Artifacts**.
9. Descarga `StoryboardStudio-debug-apk.zip` y descomprímelo. Dentro estará `app-debug.apk`.
10. Transfiere `app-debug.apk` a la tablet, ábrelo desde **Mis archivos** y sigue las instrucciones de instalación de Android.

La compilación usa Java 17, Gradle 8.9 y Android SDK 35 en los servidores de GitHub. El APK generado es una compilación de depuración para instalación y pruebas, no una versión firmada para publicar en Google Play.

## Abrir y compilar localmente (opcional)

1. Instala Android Studio.
2. Abre esta carpeta desde **Open**.
3. Permite que Gradle sincronice y descarga Android SDK Platform 35 si lo solicita.
4. Ejecuta **Build → Build Bundle(s) / APK(s) → Build APK(s)**.

## Compatibilidad

- `minSdk 23`, `targetSdk 28`, `compileSdk 35`.
- La familia Galaxy Tab S6 Lite tiene distintas generaciones. La lectura de presión depende del modelo, firmware y S Pen; debe validarse en la tablet concreta.

## Funciones pendientes

Esta es una primera base funcional, no la versión final. Todavía faltan capas editables reales con visibilidad y opacidad, miniaturas dibujadas en la timeline, edición multipista con clips y forma de onda, duraciones ajustables, sincronización de audio, guardado/reapertura de proyectos editables, exportación MP4/animática y rechazo avanzado de palma. El MP3 seleccionado se reproduce durante la sesión, pero todavía no se copia dentro del paquete del proyecto.
