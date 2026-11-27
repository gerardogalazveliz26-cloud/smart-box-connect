# Firma de las apps NexumID

`nexumid.jks` es la clave con la que se firman **NexumID IPTV** y **NexumID Panel**. Gracias a ella, cada versión nueva se instala encima de la anterior sin desinstalar y sin perder los datos.

- Los workflows la usan automáticamente.
- Si algún día cargas los secretos `NEXUMID_KEYSTORE_B64`, `NEXUMID_KEYSTORE_PASSWORD`, `NEXUMID_KEY_ALIAS` y `NEXUMID_KEY_PASSWORD` en GitHub, se usan esos en su lugar. Si son de esta misma clave, la firma no cambia.
- **No la borres ni la cambies**: con otra clave, las apps ya instaladas no podrán actualizarse.

Aviso: el repositorio es público, así que cualquiera puede ver esta clave. No instales NexumID desde fuentes que no sean este repositorio.
