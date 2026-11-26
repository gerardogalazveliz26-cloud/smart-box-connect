# DJ Chany IPTV

Reproductor Xtream Codes para Android TV, TV Box y celulares (Android 5.0 o superior).

## Funciones

- Inicio de sesión con servidor, usuario y contraseña. Puedes guardar varias listas y cambiar entre ellas.
- **TV en vivo** con categorías, vista previa, pantalla completa, cambio de canal con ▲▼ / CH+ CH−, marcado por número, guía (EPG) actual/siguiente, favoritos, recientes y reconexión automática.
- **Películas y series** en grilla de pósters, con ficha (sinopsis, reparto, puntuación), orden por recientes, A-Z o puntuación, temporadas y episodios.
- **Seguir viendo**: continúa donde lo dejaste; al terminar un episodio pasa al siguiente.
- **Búsqueda global** de canales, películas y series.
- Reproductor Media3/ExoPlayer: TS y HLS, pistas de audio y subtítulos, adelantar o retroceder con ◀▶, formato de imagen con MENÚ.
- Control parental con PIN (por defecto `0000`).
- Ajustes de formato, buffer y User-Agent.
- Actualizaciones automáticas desde `updates/djchany-iptv.json`.
- **Activación remota**: cada equipo muestra un código `DJ-XXXXX-XXXXX`; desde el [panel](../panel/) cargas, cambias o das de baja sus listas sin tocar el equipo.
- Video de presentación al abrir (se puede desactivar en Ajustes).
- Modo **celular** o **Android TV**, elegido al primer inicio.

## Compilar

El workflow `.github/workflows/djchany-iptv.yml` compila el APK en cada push que toque esta carpeta y lo publica en *Releases* con el tag `djchany-iptv-v<versión>`.

Para publicar una versión nueva, sube `versionCode` y `versionName` en `app/build.gradle.kts`.

### Firma (importante para poder actualizar)

Agrega estos secretos en *Settings → Secrets and variables → Actions*:

| Secreto | Valor |
|---|---|
| `DJCHANY_KEYSTORE_B64` | el archivo `.jks` en base64 |
| `DJCHANY_KEYSTORE_PASSWORD` | contraseña del keystore |
| `DJCHANY_KEY_ALIAS` | alias de la clave |
| `DJCHANY_KEY_PASSWORD` | contraseña de la clave |

Sin ellos, el APK se firma con una clave temporal y no se puede instalar encima de una versión anterior.

### Servidor precargado (opcional)

En `gradle.properties`, pon `djchany.defaultServer=http://tu-servidor:puerto` para que aparezca escrito en la pantalla de inicio de sesión.
