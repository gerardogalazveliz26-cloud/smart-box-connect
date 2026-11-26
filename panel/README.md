# Panel NexumID IPTV

Página para cargar listas en los equipos de tus clientes con su **código de activación** (`NX-XXXXX-XXXXX`, aparece en la app en «Activar con código de equipo» o en Ajustes).

## Cómo abrirlo

- **GitHub Pages** (recomendado): Settings → Pages → *Deploy from a branch* → rama `main`, carpeta `/ (root)`. El panel queda en
  `https://gerardogalazveliz26-cloud.github.io/smart-box-connect/panel/`
- O descarga `index.html` y ábrelo con Chrome.

## Primera vez

1. GitHub → Settings → Developer settings → **Fine-grained tokens** → *Generate new token*.
2. *Repository access*: solo `smart-box-connect`. *Permissions* → **Contents: Read and write**.
3. Pega el token en el panel → «Guardar y probar». Queda guardado solo en ese navegador.

## Seguridad

- Cada activación se guarda en `activaciones/<id>.json` **cifrada con AES-256-GCM**; la clave sale del código del equipo (PBKDF2-SHA256). Sin el código, el archivo no se puede leer aunque el repositorio sea público.
- El nombre del archivo es un hash del código, y los mensajes de commit no incluyen ni el código ni el cliente.
- No compartas tu token. Si se filtra, bórralo en GitHub y crea otro.
