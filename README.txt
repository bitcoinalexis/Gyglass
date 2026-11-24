GyGlass
=======

App Android que sustituye a la app oficial de las gafas inteligentes AIMB-G2 PRO.
Su objetivo es convertirlas en un asistente visual para personas ciegas, usando
una IA elegida por el usuario.


QUÉ HACE
--------
- Conexión BLE con las gafas, recuerda el dispositivo y se reconecta sola.
- Servicio en primer plano que sigue activo con la pantalla apagada.
- Foto con el botón de las gafas (2 clics) o a petición por voz. La imagen se
  envía a la IA y la respuesta se lee en voz alta por los altavoces de las gafas.
- Preguntas por voz con el botón (1 clic) o con la palabra de activación
  "Hey CLEO" (reconocimiento offline con Vosk usando el micrófono de las gafas).
  Si la pregunta es visual, toma una foto automáticamente.
- Muestra la batería de las gafas.
- Interfaz sencilla y compatible con TalkBack. Hay un "Modo desarrollador" con
  herramientas técnicas (registro, escaneo WiFi, pruebas de protocolo).


PROVEEDORES DE IA
-----------------
- DeepSeek (por defecto)
- Claude (Anthropic)
- Gemini (Google)

Las API keys se introducen desde Ajustes dentro de la app y se guardan solo en
el teléfono. El repositorio no contiene ninguna clave.


REQUISITOS Y COMPILACIÓN
------------------------
- Android 6.0 (API 23) o superior. Target API 36.
- Android Studio con AGP 8.13 y Kotlin 2.2.

    ./gradlew assembleDebug

La app oficial de las gafas no debe estar ejecutándose a la vez, porque las dos
compiten por la conexión BLE.


PROTOCOLO BLE DE LAS GAFAS
--------------------------
Obtenido por ingeniería inversa.

Servicio:  de5bf728-d711-4e47-af26-65e3012a5dc7
Notify:    de5bf729-d711-4e47-af26-65e3012a5dc7
Write:     de5bf72a-d711-4e47-af26-65e3012a5dc7 (write without response)

Formato de trama:
    BC [cmd] [longitud LE, 2 bytes] [CRC16 LE, 2 bytes] [payload]

CRC: CRC-16/MODBUS (poly 0xA001, init 0xFFFF) calculado sobre el payload.
Con payload vacío el CRC es FF FF.

Eventos (cmd 0x73):
    02 00 21 MODO 00   foto anunciada (MODO 02 = push por botón, 01 = pull)
    03 01              inicio de escucha
    0A 01              fin de escucha
    0C 01              fin o timeout de la sesión de IA

Foto (cmd 0xFD):
    payload: 01 [total LE] [índice LE] + fragmento JPEG
    Unos 33 fragmentos de 1013 bytes (búfer de 32 KB).
    En modo pull cada fragmento se pide con FD y payload {01, índice LE}.

Audio (cmd 0x59):
    Opus (SILK) 16 kHz, 40 bytes cada 20 ms. Se decodifica con MediaCodec
    "audio/opus".

Control (cmd 0x41):
    foto para IA     {2,1,6,2,2}
    foto normal      {2,1,1}
    iniciar vídeo    {2,1,2}
    detener vídeo    {2,1,3}

Otros:
    0x48             las gafas esperan respuesta de la app
    0x42 {0,0}       batería: respuesta payload[0] = %, payload[1] == 1 cargando


ESTADO
------
Funciona: conexión, reconexión, fotos, voz, "Hey CLEO", respuestas por TTS y batería.

Pendiente:
- Probar con la app oficial desinstalada y hacer el emparejamiento inicial
  desde GyGlass.
- Transcripción local con SpeechRecognizer (falla en algunos dispositivos).
- Actualización de firmware, volumen, gestos y sincronización WiFi, que por
  ahora solo hace la app oficial.


AVISO
-----
Proyecto independiente, sin relación con el fabricante de las gafas.
