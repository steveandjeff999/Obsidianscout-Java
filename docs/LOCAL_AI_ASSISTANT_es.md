# Asistente de IA local

ObsidianScout puede ejecutar pequeños modelos de lenguaje (Llama 3.2, Qwen2.5 y Gemma 4) **completamente dentro de tu navegador**. Tus preguntas, notas y datos de scouting nunca se envían a un servicio de IA: el servidor solo entrega los archivos del modelo y todo lo demás ocurre en tu dispositivo.

El asistente está **desactivado por defecto** para todos los usuarios.

## Qué hace

- **Página del Asistente de scouting** (`/assistant`): haz preguntas como "Top 5 equipos en autónomo", "Compara 254 y 1678", "Vista previa del partido 35" o "¿A quién deberíamos elegir?". Las tablas y gráficas se calculan directamente a partir de tus datos. La respuesta escrita la genera el modelo, y cualquier número que no se encuentre en tus datos aparece subrayado para que lo verifiques.
  También responde a "¿Quién es más consistente en teleop?", "¿Quién ha mejorado en sus últimos 3 partidos?", "Nuestros próximos partidos", "¿254 ha jugado contra 1678?", "¿Qué tan fuerte sería una alianza de 254, 1678 y 118?", "¿Cuál es el promedio del evento en endgame?", "¿Cuáles son las fortalezas y debilidades de 4414?", "¿Qué equipos se anotaron por defensa?", "¿Qué equipos tienen swerve?" y "¿Qué equipos faltan por scoutear?". El modelo sabe exactamente qué tablas y gráficas se muestran sobre su respuesta, así que las comenta en lugar de reconstruirlas.
- **Cualquier tabla o gráfica que pidas**: indica los equipos, las columnas (cualquier métrica, máx. / mín. / desviación, partidos scouteados, puesto oficial o aritmética como "EPA - xP"), filtros ("EPA mayor que 60"), orden y número de filas, como tabla o como gráfica de barras, apiladas, líneas, dispersión, radar, caja o pastel. Después cámbiala con palabras: "quita OPR de esa tabla", "solo los 10 mejores", "ordena por xP", "añade autónomo", "muéstralo como gráfica de pastel". Las tablas y gráficas siempre las construye el código a partir de tus datos; el modelo solo decide qué mostrar y lo comenta.
- **Resúmenes de notas**: en el perfil de un equipo y en la página de Datos cualitativos, el botón "Resumir notas" agrupa lo que escribieron los scouts en fortalezas y debilidades.
- **Ordenar nota**: durante el scouting cualitativo, el botón "Ordenar nota" sugiere una versión más limpia de la nota (ortografía, abreviaturas). Tú decides si usarla.

El asistente solo puede ver los datos a los que tu cuenta ya tiene acceso. No puede modificar nada.

## Activarlo

1. Abre **Configuración → Personal** y pon **Asistente de IA local** en **Activado**.
2. En **Modelos de IA en este dispositivo**, elige un modelo y pulsa **Descargar**. La descarga se hace una vez por dispositivo; usa Wi-Fi, idealmente antes del evento.
3. Aparecerá el enlace **Asistente** en la barra lateral.

Al desactivar la opción se ocultan todas las funciones de IA, y se te ofrece borrar los modelos descargados del dispositivo.

## Modelos

| Modelo | Tamaño | Funciona en | Ideal para |
|---|---|---|---|
| **Lite** (Llama 3.2 1B) | ~680 MB (GPU) o ~1,6 GB (CPU) | Casi cualquier dispositivo; no necesita GPU (más lento) | Resúmenes de notas y consultas rápidas. Las respuestas se construyen directamente a partir de tus datos. |
| **Estándar** (Qwen2.5 1.5B) | ~880 MB | Portátiles y teléfonos recientes con WebGPU | Respuestas escritas mejores y mejor comprensión de preguntas |
| **Gemma 4 E2B** (QAT para dispositivos) | ~2,5 GB | Portátiles con WebGPU; teléfonos recientes de gama alta (lento) | Mejor razonamiento y uso de herramientas que los modelos Qwen de velocidad similar |
| **Avanzado** (Qwen2.5 3B) | ~1,75 GB | Portátiles y ordenadores con una GPU capaz | Preguntas de varios pasos y estrategia (selecciones, planes de partido) |
| **Gemma 4 E4B** (QAT para dispositivos) | ~3,5 GB | Portátiles y ordenadores con GPU capaz y 8 GB+ de memoria | El modelo más capaz: preguntas de varios pasos, estrategia y documentos |

La elección del modelo se guarda **por dispositivo**. Los modelos que tu dispositivo no puede ejecutar aparecen deshabilitados con el motivo.

**Navegadores:** todos los modelos salvo Lite necesitan WebGPU con soporte de shaders de 16 bits (Chrome o Edge recientes, Safari 26+). Lite también funciona sin WebGPU, en la CPU. Los modelos Gemma 4 escriben más despacio que los Qwen, pero razonan mejor.

**Problemas:** si las respuestas salen sin sentido o la página falla, marca "no usar la GPU" en el panel de modelos, o borra y vuelve a descargar el modelo.

## Para administradores del servidor

Los modelos se instalan una vez en el servidor y luego los navegadores los descargan desde tu servidor (`/models/...`). No se descarga nada de sitios externos durante el uso.

- **Desde la web:** Administrador de almacenamiento (administrador del sitio) → **Modelos de IA locales** → **Instalar**. El progreso se muestra en vivo; las descargas interrumpidas se reanudan y cada archivo se verifica con su hash SHA-256.
- **Desde la línea de comandos:** `obsidianscout-server --install-ai-models lite,standard,advanced`.
- Los archivos se guardan en `data/models/`. Usa `OBSIDIANSCOUT_MODELS_DIR` para otra carpeta. Los cinco modelos ocupan unos 11 GB; la instalación se rechaza si dejaría menos de `local_ai.min_free_disk_mb` (2048 MB por defecto) libres.
- En un clúster, instala los modelos en cada nodo que atiende usuarios.
- **Instalación automática:** no se descarga nada al iniciar salvo los modelos listados en `config/app-config.json`, p. ej. `"local_ai": { "auto_install_tiers": ["lite"] }`.
- **Limpieza automática:** al iniciar, el servidor borra los archivos de modelos que la versión actual ya no usa; los navegadores borran sus copias en caché la próxima vez que abren el sitio.

**Licencias:** Qwen2.5 1.5B y Gemma 4 E2B/E4B son Apache-2.0. Qwen2.5 3B usa la Qwen Research License (uso no comercial). Llama 3.2 1B usa la Llama 3.2 Community License (Built with Llama).
