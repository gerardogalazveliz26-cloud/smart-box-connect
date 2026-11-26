# SB TV — PWA para iPhone

App web instalable que abre `https://tv.chocopopflow.com/?m=1` a pantalla completa, con ícono propio y sin la barra de Safari.

## Publicar (GitHub Pages)
1. En el repo: **Settings → Pages → Source: Deploy from a branch → `main` / `(root)`**.
2. La app queda en: `https://gerardogalazveliz26-cloud.github.io/smart-box-connect/tv/`

## Instalar en el iPhone
1. Abrir el link de arriba en **Safari** (no en Chrome).
2. Tocar **Compartir → Agregar a pantalla de inicio**.
3. Abrir **SB TV** desde el ícono.

## Configuración (`index.html`)
- `URL_SITIO`: el sitio que se muestra.
- `MODO`: `"iframe"` (dentro de la app, recomendado) o `"directo"` (si el sitio bloquea iframes).
  También se puede probar sin editar con `.../tv/?modo=directo`.
- El botón ↗ abajo a la derecha abre el sitio directo si algo no carga.

Ícono: `logos/logo smartboxTV.png` (regenerado en `icons/`).
