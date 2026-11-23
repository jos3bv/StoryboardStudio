# Storyboard Studio 0.5.0

## Interfaz
- Barra superior simplificada con opciones de archivo y guardado.
- Acciones de nueva toma, duplicar, eliminar, duración y carga de audio ubicadas junto a la timeline.
- Controles de reproducción y zoom siguen siendo iconos.
- Acciones descriptivas mantienen etiquetas de texto.
- Márgenes y áreas de interacción ampliados.

## Timeline
- Zoom táctil de dos dedos, además de controles + / - y centrar.
- Botones para mover la toma seleccionada una posición antes o después.
- Se mantiene el arrastre del borde derecho para ajustar duración y el reordenamiento por arrastre.

## Sesiones y carpetas
La aplicación crea estas carpetas dentro del almacenamiento externo privado de la app:
- `Android/data/com.josebv.storyboardstudio/files/StoryboardStudio/Autosave`
- `Android/data/com.josebv.storyboardstudio/files/StoryboardStudio/Sessions`
- `Android/data/com.josebv.storyboardstudio/files/StoryboardStudio/Exports/PNG`
- `Android/data/com.josebv.storyboardstudio/files/StoryboardStudio/Exports/PDF`

Las sesiones guardadas como copias y los archivos de sesión importados/exportados se administran en Sessions. Se intenta migrar el autosave y las copias locales de versiones anteriores.

## Exportación
- Exportar el plano seleccionado a PNG.
- Exportar todos los planos a PNG.
- Exportar el storyboard completo a PDF multipágina.
- Se comprueba que los archivos se hayan creado y que la escritura termine correctamente.
- Los errores de exportación muestran el motivo recibido del sistema.

## Pincel
- Tamaño configurable.
- Opacidad configurable.
- Sensibilidad a presión del S Pen activable/desactivable.
- Las preferencias del pincel se guardan dentro de la sesión.

## Compilación
- Android Gradle Plugin 8.7.3
- Kotlin 2.0.21
- JDK 17
- compileSdk 35
- minSdk 23
