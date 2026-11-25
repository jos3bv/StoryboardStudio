BV'Storyboarder 0.9.37

Base: 0.9.36 (versionCode 47)
New version: 0.9.37 (versionCode 48)

Changes included:
- Timeline toolbar groups retain a common 40dp vertical alignment; shot label font padding disabled for improved baseline alignment.
- Reduced all left-panel tool/import/export button bounds and icon artwork to 28dp, with 1dp inter-button spacing and added panel inset.
- Narrowed the slider rail and kept the two vertical sliders flush with one another and the canvas-side edge.
- Added selected-color swatch next to the color picker.
- Plan annotation text is rendered red, including legacy saved sessions.
- Header now shows the installed app version and exposes session actions directly: Nueva, Abrir sesión, Abrir, Guardar como, Guardar copia, Guardar.
- Header text uses centered vertical alignment and tighter button spacing.
- Tool icons use 50% opacity when inactive and 100% when active; import/export controls rest at 50%.
- Added provisional lasso tool: freehand selection of portions of strokes, one-finger move, two-finger rotate/scale, Aceptar and Cancelar. Selected portions remain editable stroke data; untouched portions remain in place.
- Reduced live stroke path sampling cap to reduce redraw cost during long strokes; historical stylus samples remain processed.
- Existing global Undo/Redo history and image caching are retained; lasso confirmation triggers the existing history/autosave path.

Build status:
- Source ZIP integrity checked.
- Android/Gradle build was not run in this environment (no Gradle wrapper or Android SDK installed).
- Please run the repository GitHub Actions build before installing the APK.
