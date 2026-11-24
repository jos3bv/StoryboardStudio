# Storyboard Studio 0.9.9

Rediseño de interfaz basado en las referencias visuales de BV’StoryboardStudio.

## Cambios de interfaz
- Barra superior con Archivo, Guardar y marca a la derecha.
- Panel izquierdo con selector cromático permanente, siete colores recientes y escala de grises.
- Dos deslizadores verticales permanentes: tamaño y opacidad.
- Herramientas en una cuadrícula de tres columnas por dos filas.
- Exportación PNG del plano, PNG de todos los planos y PDF completo.
- Área central ampliada con controles de deshacer, rehacer y centrar vista.
- Timeline reorganizada con gestión de planos a la izquierda, reproducción centrada y zoom a la derecha.
- Bloques de planos con nombre y duración; plano seleccionado en azul.
- Pista de audio verde con acceso directo para cargar audio.
- Eliminación del panel derecho.

## Compatibilidad
Se conserva el motor de dibujo, el uso del S Pen, importación de imágenes, sesiones JSON, guardado automático, audio, navegación, reproducción y exportación.

## Compilación
El proyecto se compila mediante el workflow de GitHub Actions incluido en `.github/workflows/build-apk.yml`.

Versión: 0.9.9 (versionCode 11).


## Iconos SVG
Los iconos entregados se conservan en `app/src/main/assets/icons_svg/` y sus versiones rasterizadas de alta resolución están en `app/src/main/res/drawable-nodpi/` para mostrarse nítidamente en Android.


## Versión 0.9.9
Ajustes de interfaz basados en las anotaciones: menú Archivo desplegable, panel de color ampliado, controles SVG sin fondo, deshacer/rehacer en el encabezado, timeline a ancho completo, progreso de carga de audio y caché de dibujo para mejorar la fluidez.


## Versión 0.9.9
Ajustes de encabezado, selector cromático HSV, muestras cuadradas, feedback visual de botones y espaciado/alineación de controles de timeline.


## Versión 0.9.9
Corrección de compilación: se declara el estado `headerStatusMessage` utilizado por el encabezado para mostrar el plano seleccionado y los mensajes de estado.


## Versión 0.9.9
Mejoras en carga de audio anclada al viewport, borrador real con borrado de píxeles, separación táctil de rueda cromática, ajuste de zoom para encajar audio y planos, controles SVG con feedback de opacidad solo en el icono y guardado automático periódico cada 15 segundos.


## Versión 0.9.9
El cabezal de reproducción se puede arrastrar por toda la duración del audio, incluso fuera de los planos. La barra de controles deja las herramientas de planos a la izquierda, centra la reproducción en el espacio restante y fija la navegación/zoom a la derecha. El ajuste de una transición modifica las duraciones de ambos planos contiguos de forma inversa, manteniendo fijo el final del segundo hasta que alcanza 0,5 s; a partir de ese límite, el extremo final también puede desplazarse.


## Versión 0.9.11
- Los valores de tamaño y opacidad aparecen junto al control únicamente mientras se manipula.
- El borrador no muestra un punto inicial antes de comenzar el movimiento.
- Etiqueta del plano centrada entre eliminar y los controles de duración.
- La pista de audio vacía abre el selector de audio y el botón visual es más compacto.
- Sesiones y autosave en Documentos/Storyboarder; archivos de sesión con extensión corta `.sbd`.
- Cuadrado cromático ampliado con separación respecto de la rueda.
- Estados de iconos SVG: 75% normal, 90% hover y 100% al presionar, con breve retención del estado presionado.


## Versión 0.9.11
- Límite de transición entre planos fijado explícitamente en 500 ms (0,5 s).
- El ajuste de zoom total actualiza también la posición del control deslizante de zoom.
- Capa de tinta transparente sobre el lienzo blanco para que el borrador elimine únicamente los trazos.
- Eliminado el borde del lienzo que se transformaba al rotarlo.
- Espaciado uniforme de la cuadrícula de herramientas con un solo margen compartido entre botones.
- Valor de los deslizadores mostrado a la derecha del manejador.
- Exportaciones dirigidas a Documentos/Storyboarder/Exports/PNG y /PDF.
- Guardado y apertura de sesiones centralizados en Documentos/Storyboarder/sessions.
- Importación de imágenes mediante el selector de imágenes del dispositivo.
