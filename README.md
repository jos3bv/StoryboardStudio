# Storyboard Studio 0.8.1

Actualización de Storyboard Studio para Android.

## Novedades
- Importación de imágenes desde el dispositivo al plano seleccionado. La imagen se ajusta proporcionalmente al lienzo y queda incrustada en el JSON de la sesión para no depender del archivo original.
- Tamaño de pincel ampliado de 40 a 200.
- Tamaño del borrador independiente del tamaño del pincel; ambos usan el mismo control y conservan sus valores por separado.
- Selector de color con rueda cromática, cuadro de saturación/brillo y control de opacidad.
- Regla de tiempo de la línea de tiempo con formato minutos:segundos y densidad de etiquetas adaptada al zoom.

## Compilación
El proyecto se compila mediante el workflow de GitHub Actions incluido en `.github/workflows/build-apk.yml`.


Corrección 0.8.1: se consolidó el manejo de resultados de selección de archivos en un único onActivityResult para evitar la declaración duplicada que impedía compilar. La importación de imágenes, audio y sesiones se procesa desde ese callback.
