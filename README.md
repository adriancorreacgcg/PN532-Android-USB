# PN532 USB Reader para Android

**Compatible con el hardware probado:** teléfono Android con OTG USB-C, adaptador USB-TTL Silicon Labs CP210x, módulo Elechouse PN532 V3 en modo HSU/UART (switches 0/0), 115200 baudios.

## Funciones

- Detecta un adaptador USB serial (prioridad al CP210x VID 10C4).
- Solicita permiso USB nativo de Android.
- Consulta firmware PN532 y muestra versión.
- Inicializa el PN532 con SAMConfiguration.
- Realiza una lectura ISO14443-A (InListPassiveTarget) por pulsación.
- Muestra UID, ATQA, SAK y ATS cuando la tarjeta lo entrega.
- Muestra un registro TX/RX. No guarda claves ni datos bancarios.

## Uso

1. Conecta el CP210x al teléfono por USB-C OTG y conserva el cableado funcional del PC.
2. Abre **PN532 USB Reader**, toca **Conectar USB**, acepta el permiso.
3. Pulsa **Consultar firmware** → **Inicializar PN532**.
4. Acerca una tarjeta o etiqueta NFC y pulsa **Leer tarjeta NFC**.
5. Puede bloquearse esperando una tarjeta: es una lectura por pulsación, no un escaneo continuo.

## Montaje de archivos desde GitHub (Android o PC)

Crea un repositorio nuevo **PN532-Android-USB** con rama principal **main** y copia exactamente las rutas del proyecto. Ejemplo: al crear un archivo llamado `app/src/main/java/com/pn532/usbreader/MainActivity.java`, GitHub crea las carpetas automáticamente. Los directorios de Git no existen sin archivos.

**Importante:** subir únicamente `PN532-Android-USB.zip` a GitHub NO descomprime su contenido ni inicia la compilación. Debes subir los archivos del ZIP descomprimidos, cada uno en su ruta.

## Compilación automática / Latest

El workflow `.github/workflows/build-apk.yml` corre al subir cambios a **main**, y también por **Actions → Compilar y publicar APK → Run workflow**.

- Si termina correctamente, descarga el APK en `https://github.com/TU_USUARIO/PN532-Android-USB/releases/latest` (sustituye TU_USUARIO).
- También queda en **Actions → último workflow → Artifacts**, aunque GitHub puede requerir iniciar sesión para artifacts.
- Permite ejecución de Actions y creación de Releases en la configuración del repositorio si GitHub la bloquea.
- Cada ejecución genera un APK **debug** firmado temporalmente. No se garantiza mantener la firma entre versiones: al actualizar podría requerir desinstalar la versión previa y perder sus datos. Para actualizaciones instalables sobre la versión previa hace falta una keystore privada persistente en GitHub Secrets y compilar release firmado.
- Si usas repo privado, el enlace de Latest exige iniciar sesión.
- GitHub Actions compila y publica; no instala automáticamente la aplicación en el teléfono.

## Estructura

```
.github/workflows/build-apk.yml
.gitignore
settings.gradle
build.gradle
gradle.properties
app/build.gradle
app/src/main/AndroidManifest.xml
app/src/main/res/layout/activity_main.xml
app/src/main/java/com/pn532/usbreader/Pn532Protocol.java
app/src/main/java/com/pn532/usbreader/MainActivity.java
README.md
```

## Notas

- App para identificación de tarjetas NFC, no para emular tarjetas bancarias ni clonar credenciales.
- Una respuesta `D5 4B` puede incluir ATS solo en tarjetas ISO14443-4; UID y SAK no prueban acceso a aplicaciones seguras.
- El log registra información pública del anticollision (UID). No compartas UID si te preocupa la privacidad.
- La dependencia `com.github.mik3y:usb-serial-for-android:3.11.0` se descarga desde JitPack.
