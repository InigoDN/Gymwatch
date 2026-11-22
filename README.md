# Diamantizador (GymWatch)

Cliente **Wear OS 2 (Android 9 / API 28)** para el **Mobvoi TicWatch Pro** (`armeabi-v7a`) pensado para controlar tus entrenamientos de gimnasio directamente desde la muñeca sin sacar el móvil del bolsillo. Se sincroniza en tiempo real con la app compañera del teléfono mediante **Wearable Data Layer (`MessageClient`)**.

---

##  Características principales

* **Selección e inicio de rutina desde el reloj:**
  * Muestra la lista de sesiones enviadas desde el móvil destacando la rutina de hoy (`HOY`).
  * Reintento automático de conexión con el teléfono al abrir la app y botón manual de reconexión.
* **Registro rápido de series (`MainActivity`):**
  * Muestra el progreso actual (`Ejercicio X/Total • Serie Y/Objetivo`), rango de repeticiones objetivo, consejo de progresión y marca anterior.
  * **Steppers circulares (`−` / `+`)** para **Peso (kg)** (paso adaptativo de `1,25 kg` en cargas `< 16 kg` y `2,5 kg` en superiores) y **Repeticiones**.
  * **Teclado numérico de precisión:** tocando sobre el peso o las repeticiones se abre un teclado exacto (`0–9`, `.`, `⌫`) con accesos rápidos de ajuste fino (`±0.5`, `±1`, `±2.5 kg` / `±1`, `±2`, `±5 reps`).
  * **Selector de RIR (`RIR 0`, `RIR 1`, `RIR 2`, `3+`)** con código de colores.
  * **Selector de ejercicio:** permite saltar a cualquier otro ejercicio de la sesión viendo cuántas series llevas completadas en cada uno.
* **Temporizador de descanso con anillo circular (`CircularProgressRingView`):**
  * Aro perimetral alrededor de la esfera del reloj con código de color según estado (verde en descanso, rojo en los últimos `10 s`, azul en transición entre ejercicios y amarillo en pausa).
  * Controles directos en pantalla: `-30s`, `⏸ / ▶`, `+30s` y `▶ SIGUIENTE SERIE`.
  * **Alarma de fin de descanso:** enciende la pantalla (`WakeLock`), lanza un patrón de vibración múltiple y emite un pitido por el canal de alarma (`STREAM_ALARM`).

---

##  Protocolo de sincronización (Wearable Data Layer)

Esta app funciona como **companion** (`standalone = false`) comunicándose con la app del móvil a través de `Wearable.getMessageClient`:

* **Reloj ⬅ Móvil (`/gym/state`):** recibe un JSON con el estado del entrenamiento (`active`, `routines`, ejercicio/serie actual, pesos/reps sugeridos, historial previo y temporizador de descanso por `targetEndEpochMs`).
* **Reloj ➡ Móvil (`/gym/command`):** envía acciones JSON:
  * `REQUEST_SYNC`
  * `START_ROUTINE_FROM_WATCH` (`routineDayId`)
  * `SELECT_EXERCISE` (`exerciseIndex`)
  * `COMPLETE_SET` (`weightKg`, `reps`, `rir`)
  * `ADD_REST_TIME` (`seconds`)
  * `TOGGLE_PAUSE`
  * `SKIP_REST`

---

## Requisitos y compilación

* **Dispositivo objetivo:** Wear OS 2 (`minSdk = 28`, `targetSdk = 28`).
* **Arquitectura:** `armeabi-v7a`.
* **Dependencias:** `androidx.wear:wear`, `play-services-wearable`.
* **Compilación:** Android Studio / Gradle (`compileSdk = 34`, Java 11, Kotlin).

---

## Instalación por ADB (Sideloading)

> **Nota:** Requiere tener instalada en el teléfono emparejado la app compañera compatible con el mismo `applicationId` (`com.example.diamantizador`) y los mensajes `/gym/state` y `/gym/command`.

Descarga el `.apk` desde **[Releases](../../releases)** e instálalo en el reloj:

```powershell
adb connect <IP_DEL_RELOJ>:5555
adb install -r Diamantizador-Watch.apk
```