# ScreenTrans — Technical Repository Report

## 1. Executive summary
ScreenTrans es una app Android de traducción en pantalla que combina captura de pantalla (MediaProjection), OCR local (ONNX Runtime) y traducción por API compatible con formato OpenAI Chat Completions. Su objetivo es superponer texto traducido exactamente sobre el texto original.

Madurez técnica observada: **etapa temprana funcional**. El flujo principal existe de extremo a extremo (captura -> OCR -> postproceso -> traducción -> overlay), pero todavía hay riesgos de robustez (lifecycle/permisos OEM), cobertura de pruebas casi inexistente y dependencias operativas del entorno (SDK Android local, permisos especiales de ROMs).

## 2. Repository and build overview
- Tipo de proyecto: app Android monomódulo (`:app`) con Gradle Kotlin DSL.
- Nombre de proyecto: `ScreenTrans`.
- Namespace / applicationId: `com.longipinnatus.screentrans`.
- SDKs:
  - `minSdk = 26`
  - `targetSdk = 37`
  - `compileSdk = 37`
- Versión:
  - `versionCode = 26051001`
  - `versionName = 0.1.3`
  - `versionNameSuffix = -alpha`
- Lenguaje/toolchain: Kotlin + Java 17.
- UI: Jetpack Compose para Activities; overlay/selección en Views/WindowManager (enfoque mixto).
- Splits ABI activos: `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64` + universal APK.
- Signing:
  - `debug`: opcional vía `local.properties`.
  - `release`: `app/release.keystore` + variables de entorno `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
- Release config: `minifyEnabled=true`, `shrinkResources=true`, ProGuard optimizado.
- Dependencias clave:
  - ONNX Runtime Android
  - OkHttp
  - Gson
  - Coroutines
  - DataStore Preferences
  - Compose Material3

Estado de build local (en este entorno):
- `./gradlew tasks --all`: **OK**.
- `./gradlew assembleDebug`: **FALLA** por falta de SDK Android (`ANDROID_HOME` o `local.properties sdk.dir`).

Diagnóstico de compilación:
- El repo es **teóricamente compilable** desde clon limpio si se configura SDK Android y JDK 17.
- Fallo actual no indica bug del código app, sino configuración de entorno.

## 3. Functional architecture
Entradas principales:
- `MainActivity`: UI principal, solicita permisos, arranca/detiene servicio.
- `FloatingService`: servicio foreground que mantiene MediaProjection, botón flotante, captura, OCR/traducción y overlay.
- `ProjectionProxyActivity`: activity transparente para re-solicitar permiso de captura cuando se pierde (pantalla apagada/rotación/revocación).
- `QuickSettingsService`: tile QS para iniciar/detener rápidamente.

Capas funcionales:
1. **Permisos y arranque**: `MainActivity`, `ProjectionProxyActivity`.
2. **Captura de pantalla**: `FloatingService` + `ScreenCaptureHelper`.
3. **OCR local**: `OcrEngine` + assets ONNX (`det_mobile.onnx`, `rec_mobile.onnx`, `dict.txt`).
4. **Postprocesado OCR**: `OcrPostProcessor` (merge, filtros, orientación, color dominante).
5. **Traducción remota**: `TranslationEngine` (chat/completions normal o streaming SSE-like).
6. **Render y salida**: `OverlayManager` + `OverlayView` + clipboard opcional.
7. **Configuración/estado**: `PreferenceManager` (DataStore), `AppSettings`, `TokenStatsManager`, `LogManager`.

## 4. Main user workflow
Flujo implementado:
1. Usuario abre app y pulsa iniciar.
2. Se solicitan permisos overlay y notificaciones (Android 13+).
3. Se solicita MediaProjection.
4. Se inicia `FloatingService` en foreground.
5. Usuario toca/doble-toca botón flotante (modo región o franja vertical; o pantalla completa según ajuste).
6. Servicio captura bitmap actual.
7. OCR detecta cajas y reconoce texto.
8. Postprocesador filtra/combina bloques.
9. Si `ocrOnly=false`, se envía JSON de bloques a API LLM.
10. Overlay muestra OCR primero, luego traducción incremental/final.
11. Opcional: auto-hide y copia a portapapeles.

## 5. OCR pipeline analysis
Implementación observada:
- Motor: ONNX Runtime local.
- Modelos por defecto: `app/src/main/assets/det_mobile.onnx` y `rec_mobile.onnx`.
- Diccionario: `dict.txt` cargado en memoria para decodificación CTC-like.
- Modelos custom: soportados por paths configurables (`detCustomModelPath`, `recCustomModelPath`).

Preproceso:
- Reescala imagen al lado máximo ~960, ajustando a múltiplos de 32.
- Normaliza en orden BGR con mean/std tipo PaddleOCR.

Detección:
- Inferencia tensor `[1,3,H,W]`.
- Recorrido de mapa de probabilidad + flood fill por componente conectada.
- Umbrales configurables: `pixelThresh`, `boxThresh`, `unclipRatio`.

Reconocimiento:
- Crop por caja -> resize a alto fijo (48) -> tensor -> decode por argmax y colapso repetidos/blanks.
- Soporte vertical: rotación cuando orientación detectada/configurada.

Postproceso:
- `OcrPostProcessor` fusiona elementos->líneas->bloques (algoritmo union-find + heurísticas de overlap/gap).
- Soporta heurísticas para CJK y saltos de línea/indentación.
- Filtros por tamaño+regex sobre entidades raw y/o merged.

Posibles cuellos de botella/riesgos:
- OCR ejecuta múltiples loops intensivos por píxel y por caja (CPU alto en capturas grandes).
- `FloatBuffer.allocate(...)` y conversiones bitmap frecuentes -> presión GC.
- Riesgo de OOM/latencia en dispositivos modestos (aunque `largeHeap=true` mitiga parcialmente).
- Re-inicializaciones de `OcrEngine` por cambios de settings en caliente pueden impactar UX.

Idiomas OCR:
- README afirma CN/EN/JP; en código no hay lista explícita de idiomas del modelo, pero `dict.txt` + modelos bundled sugieren cobertura multilenguaje CJK/latino. **No confirmado en el código inspeccionado** el alcance exacto por carácter.

## 6. Translation/API pipeline analysis
Conectividad API:
- Endpoint base configurable (`baseUrl`) + ruta fija `/chat/completions`.
- Fetch de modelos en `/models`.
- Header `Authorization: Bearer <apiKey>`.
- Diseño compatible con APIs estilo OpenAI Chat Completions.

Payload y formato:
- Envía `messages=[system,user]` donde `user` contiene array JSON con `{id,text}`.
- Puede forzar `response_format = {type: json_object}`.
- Soporta parámetros extra JSON (`extraParams`) mergeados sin sobreescribir claves base.

Streaming:
- Modo stream habilitable con callback incremental.
- Parser por líneas `data:` + fallback final de parseo robusto.
- Captura `usage` en stream_options cuando el backend lo retorna.

Cancelación/errores/timeouts:
- `connectTimeout=30s`, `readTimeout=90s`, `writeTimeout=30s`.
- `TranslationEngine.cancel()` cancela llamada activa al cerrar overlay.
- Manejo de error asigna `[Exception]` en bloques no traducidos.
- No hay retry/backoff explícito.

Costes y tokens:
- `TokenStatsManager` persiste estadísticas de sesión/día/mes/total.
- Precios/currency configurables en settings para cálculo de coste (UI/estadísticas).

Riesgos:
- Sin debounce/rate-limit fuerte en taps repetidos, pueden dispararse múltiples solicitudes (mitigado parcialmente con flags de procesamiento, pero no totalmente confirmado en todas las rutas).
- Si prompt/params producen salida no JSON, hay fallback agresivo; aun así puede fallar parseo.

## 7. Android permissions and lifecycle behavior
Permisos en manifest:
- `INTERNET`: llamadas API.
- `FOREGROUND_SERVICE`: servicio persistente.
- `FOREGROUND_SERVICE_MEDIA_PROJECTION`: captura de pantalla foreground.
- `SYSTEM_ALERT_WINDOW`: overlays flotantes.
- `POST_NOTIFICATIONS`: notificación foreground en Android 13+.

Comportamiento de permisos críticos:
- Overlay: solicitado desde `MainActivity` vía `ACTION_MANAGE_OVERLAY_PERMISSION`.
- MediaProjection: solicitado al iniciar; re-solicitado mediante `ProjectionProxyActivity` si recursos no listos.
- Notificación: runtime request en Android 13+.
- Clipboard: comprobación AppOps `android:write_clipboard` antes de copiar (especialmente para ROMs restrictivas).

Lifecycle/rotación/pantalla apagada:
- `FloatingService.onConfigurationChanged` recrea VirtualDisplay.
- Si projection cae, callback detiene recursos; interacción posterior intenta re-lanzar proxy activity.
- README y código coinciden en necesidad de reautorizar tras screen-off/rotación.

Riesgos de compatibilidad (Android 13-16, MIUI/HyperOS, etc.):
- Restricciones BAL (background activity launch) se abordan con PendingIntent + ActivityOptions en Android 14+.
- Aun así, ROMs OEM con políticas agresivas pueden bloquear overlays, clipboard o popups en background.
- targetSdk 37 puede exigir ajustes futuros al estabilizarse cambios de plataforma. **No confirmado en el código inspeccionado** comportamiento final en Android 15/16 release retail.

## 8. UI/UX analysis
Estado actual:
- Main/settings/log/stats/about en Compose.
- Selección de región y overlay de resultados en Views sobre WindowManager.
- Controles: botón flotante arrastrable, click/doble click, modo pantalla completa/región.

Fortalezas UX:
- Flujo compacto para traducción contextual.
- Transparencia, fuentes, colores adaptativos, auto-hide configurable.
- Soporte de streaming reduce espera percibida.

Fricciones detectadas:
- Alta dependencia de permisos especiales (difícil para usuarios no técnicos).
- Comportamiento variable por ROM (clipboard y popups).
- Configuración extensa para usuarios nuevos (muchos parámetros OCR/LLM).
- No se observan tutoriales in-app guiados ni validaciones profundas de endpoint/modelo.

Persistencia de estado:
- Settings persistidos en DataStore.
- Estado temporal UI Compose en activities no está diseñado para process death compleja (aceptable en etapa temprana).

## 9. Security and privacy audit
Datos que pueden salir del dispositivo:
- Texto OCR y contexto (`backgroundInfo`) enviados al endpoint LLM.
- Potencialmente prompt/sistema y bloques completos en logs internos.

Almacenamiento local sensible:
- API key guardada en DataStore (texto plano en preferencias internas).
- Stats de tokens en SharedPreferences.
- Logs en memoria/estado app (según `LogManager`), con posibilidad de incluir cuerpos request/response.

Riesgos encontrados (severidad):
- **High**: API key sin cifrado fuerte (sin Android Keystore/EncryptedSharedPreferences).
- **High**: logging de request/response puede exponer texto sensible y metadatos de uso.
- **Medium**: clipboard opcional puede exponer contenido a otras apps/servicios del sistema.
- **Medium**: endpoint configurable permite mala configuración hacia servidores no confiables.
- **Low**: no se observan claves hardcodeadas reales; hay defaults de endpoint/modelo públicos.
- **Info**: README incluye descargo de responsabilidad, pero falta una política de privacidad técnica formal detallada.

## 10. Performance audit
Costes esperados:
- CPU: OCR local + postproceso geométrico intenso.
- Memoria: múltiples bitmaps/crops/reescalados y buffers float.
- Red/latencia: depende de modelo remoto, streaming ayuda UX pero no reduce coste de inferencia remota.
- Batería: servicio foreground continuo + capturas repetidas.

Hilos/corrutinas:
- Hay uso de `Dispatchers.IO` en OCR init y tareas pesadas en partes críticas.
- Riesgo puntual de trabajo costoso en Main en rutas de UI/servicio **No confirmado en el código inspeccionado** para todas las funciones internas de `FloatingService` y `OverlayView` (archivo grande parcialmente revisado).

Optimizaciones seguras sugeridas (sin implementar):
- Pool/reuso de buffers OCR.
- Downscale adaptativo según tamaño de región.
- Límite de frecuencia de capturas/solicitudes.
- Telemetría de latencia por etapa (capture/ocr/translate/render) para tuning real.

## 11. Code quality audit
Fortalezas:
- Separación funcional razonable por componentes (OCR/API/overlay/settings).
- Configuración rica y extensible en `AppSettings`.
- Manejo consciente de edge cases Android 14+ (BAL/media projection).

Debilidades:
- Archivos grandes con múltiples responsabilidades (`FloatingService`, `SettingsActivity`, `TranslationEngine`).
- Baja cobertura de pruebas automatizadas.
- Manejo de errores heterogéneo (logs + strings fallback).
- Riesgo de estados frágiles entre servicio, overlay y callbacks de permisos.

Extensibilidad:
- Moderada: la base es usable, pero convendría modularizar más (casos de uso/repositorios/interfaces de red y OCR) antes de crecer en features.

## 12. Testing and CI status
Tests locales:
- No se detectaron directorios activos `app/src/test` ni `app/src/androidTest` en este checkout.

CI:
- Workflow GitHub Actions `android-build.yml` compila **release** en tags `v*` y workflow manual.
- Requiere secrets de firma; genera release con APKs.

Cobertura actual real:
- Se valida compilación release en CI (cuando hay secretos/config correcta).
- No hay evidencia de test unitario/instrumentado recurrente.

Plan mínimo recomendado:
1. Unit tests de parseo de respuesta LLM (normal/stream/malformado).
2. Unit tests de `OcrPostProcessor` (merge/filtros/orientación).
3. Tests instrumentados de permisos/lifecycle (rotación, revoke projection).
4. Tests con mock server (timeouts, 4xx/5xx, cancelación).
5. Smoke test UI (arranque, iniciar/parar servicio, navegación settings).

## 13. Documentation gaps
README vs código:
- En general consistente en flujo principal y naturaleza temprana del proyecto.
- Descripción de permisos especiales y reautorización coincide con la implementación.

Brechas:
- Build from source insuficiente (no guía explícita de SDK/JDK/local.properties).
- Falta guía operativa de modelos custom ONNX (formatos exactos esperados).
- Falta documentación de seguridad de API key/logs/clipboard.
- Falta troubleshooting estructurado por ROM (MIUI/HyperOS/ColorOS/etc.).
- Falta matriz de compatibilidad Android version-by-version.
- Falta guía de firma local release fuera de CI.

## 14. Risks and known limitations
- Dependencia de permisos sensibles y comportamiento OEM inconsistente.
- Posible exposición de datos sensibles en logs/clipboard.
- Robustez de parseo LLM depende de disciplina del modelo remoto.
- Sin suite de tests amplia para regresiones de lifecycle/overlay.
- Build local falla sin SDK Android configurado (riesgo onboarding contribuidores).

## 15. Improvement roadmap
### P0 (crítico)
1. **Proteger secretos en repositorio local de app**
   - Problema: API key en DataStore sin cifrado fuerte.
   - Razón: riesgo de extracción en dispositivo comprometido/backup.
   - Afecta: `PreferenceManager`, flujo settings LLM.
   - Enfoque: migrar a almacenamiento cifrado respaldado por Keystore.
   - Riesgo: Medium.
   - Complejidad: Medium.

2. **Reducir exposición de datos en logging**
   - Problema: request/response pueden contener texto sensible.
   - Razón: privacidad/compliance.
   - Afecta: `TranslationEngine`, `LogManager`, `LogActivity`.
   - Enfoque: redacción/masking + niveles de log por build type.
   - Riesgo: Low.
   - Complejidad: Low.

### P1 (importante)
1. **Pruebas automáticas base del pipeline API/OCR postprocess**
   - Problema: alta probabilidad de regresión silenciosa.
   - Afecta: `TranslationEngine`, `OcrPostProcessor`.
   - Enfoque: unit tests + fixtures.
   - Riesgo: Low.
   - Complejidad: Medium.

2. **Hardening lifecycle MediaProjection/overlay en OEMs**
   - Problema: revocaciones y restricciones de background.
   - Afecta: `FloatingService`, `ProjectionProxyActivity`, `QuickSettingsService`.
   - Enfoque: mayor observabilidad + rutas de recuperación guiadas.
   - Riesgo: Medium.
   - Complejidad: Medium.

### P2 (UX/performance)
1. **Optimización de memoria/CPU OCR**
   - Problema: overhead bitmap/buffer.
   - Afecta: `ScreenCaptureHelper`, `OcrEngine`.
   - Enfoque: pooling/reuso/downscale adaptativo.
   - Riesgo: Medium.
   - Complejidad: High.

2. **Control de frecuencia de traducciones**
   - Problema: riesgo de llamadas excesivas API por interacciones rápidas.
   - Afecta: `FloatingService`, `TranslationEngine`.
   - Enfoque: debounce/rate limit/circuit breaker simple.
   - Riesgo: Low.
   - Complejidad: Medium.

### P3 (opcional)
1. **Asistente de configuración inicial**
   - Razón: reducir fricción de onboarding.
   - Afecta: `MainActivity`, `SettingsActivity`, docs.
   - Complejidad: Medium.

2. **Métricas detalladas de etapa por etapa**
   - Razón: tuning objetivo de latencia.
   - Afecta: servicio/OCR/API/overlay.
   - Complejidad: Medium.

## 16. Files/classes map
- `app/src/main/kotlin/com/longipinnatus/screentrans/MainActivity.kt`: launcher UI y permisos.
- `.../FloatingService.kt`: núcleo runtime en segundo plano.
- `.../ProjectionProxyActivity.kt`: permiso MediaProjection transparente.
- `.../ScreenCaptureHelper.kt`: extracción Bitmap de `ImageReader`.
- `.../OcrEngine.kt`: carga ONNX + inferencia det/rec.
- `.../OcrPostProcessor.kt`: merge/filtros/formato de bloques.
- `.../OcrEntities.kt`: entidades OCR (`TextElement`, `TextLine`, `TextBlock`).
- `.../TranslationEngine.kt`: cliente HTTP LLM + parseo streaming.
- `.../OverlayManager.kt` y `.../OverlayView.kt`: render overlay, auto-hide, clipboard.
- `.../PreferenceManager.kt` + `.../AppSettings.kt`: configuración persistente.
- `.../TokenStatsManager.kt` + `.../StatisticsActivity.kt`: consumo/coste.
- `.../LogManager.kt` + `.../LogActivity.kt`: trazas internas.
- `app/src/main/AndroidManifest.xml`: permisos/componentes.
- `app/build.gradle.kts`: build Android y release.
- `.github/workflows/android-build.yml`: CI de release.

## 17. Concrete recommendations
1. Añadir sección “Build from source” paso-a-paso (JDK17, Android SDK, `local.properties`).
2. Añadir “Security/Privacy notes” explícitas: qué datos se envían y cómo desactivar logs/clipboard.
3. Introducir test unitario mínimo para parser streaming y merge OCR antes de crecer features.
4. Definir política de logs por build type (debug detallado, release reducido).
5. Añadir guía OEM (MIUI/HyperOS) con capturas y troubleshooting.
6. Documentar contrato técnico de modelos ONNX custom (input/output shapes esperados).

## 18. Questions for the maintainer
1. ¿Cuál es el upstream exacto de este fork en `acr994/ScreenTrans` y qué commits divergentes son prioritarios conservar?
2. ¿Se planea mantener compatibilidad real con Android 15/16 o fijar temporalmente target menor?
3. ¿Qué nivel de privacidad esperan en release: logs desactivados por defecto?
4. ¿Desean soporte oficial para OCR offline multilenguaje adicional (más diccionarios/modelos)?
5. ¿Se aceptará migración de API key a almacenamiento cifrado aunque implique migración de preferencias?
6. ¿Cuál es el comportamiento esperado cuando la respuesta LLM no respeta JSON estricto: fallback parcial o hard-fail visible?

---

### Nota sobre fork y cambios respecto a upstream
En este entorno local **no hay remotos Git configurados**, por lo que no fue posible comparar técnicamente con upstream/origin y cuantificar divergencia. **No confirmado en el código inspeccionado**.
