# Storyboard Studio 0.9.8

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

Versión: 0.9.8 (versionCode 11).


## Iconos SVG
Los iconos entregados se conservan en `app/src/main/assets/icons_svg/` y sus versiones rasterizadas de alta resolución están en `app/src/main/res/drawable-nodpi/` para mostrarse nítidamente en Android.


## Versión 0.9.8
Ajustes de interfaz basados en las anotaciones: menú Archivo desplegable, panel de color ampliado, controles SVG sin fondo, deshacer/rehacer en el encabezado, timeline a ancho completo, progreso de carga de audio y caché de dibujo para mejorar la fluidez.


## Versión 0.9.8
Ajustes de encabezado, selector cromático HSV, muestras cuadradas, feedback visual de botones y espaciado/alineación de controles de timeline.


## Versión 0.9.8
Corrección de compilación: se declara el estado `headerStatusMessage` utilizado por el encabezado para mostrar el plano seleccionado y los mensajes de estado.


## Versión 0.9.8
Mejoras en carga de audio anclada al viewport, borrador real con borrado de píxeles, separación táctil de rueda cromática, ajuste de zoom para encajar audio y planos, controles SVG con feedback de opacidad solo en el icono y guardado automático periódico cada 15 segundos.
