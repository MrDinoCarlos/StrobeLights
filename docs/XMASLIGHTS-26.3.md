# XmasLights y el renderer 26.3 — StrobeLights 0.10.43

En Paper 26.3, XmasLights 2.9.1 rechazaba StrobeLights 0.10.42 aunque estuviera
recién instalado. El bridge aceptaba únicamente las versiones 0.10.38 a
0.10.41 y mostraba el requisito antiguo de 0.10.38. Además, el pack 26.3 solo
decodificaba fuentes nativas y flashes: quitar la lista de versiones no habría
producido iluminación RGB.

La pareja corregida es **StrobeLights 0.10.43 + XmasLights 2.9.2**. XmasLights
valida el contrato del ZIP de la versión de Minecraft y su modelo/tinte real,
sin una lista de versiones del plugin. Los packs incompatibles siguen rechazados.
El antiguo ajuste `allow-untested-version` queda obsoleto; nunca evita la
validación. TRP sigue siendo opcional y no interviene en esta negociación.

## Protocolo y composición

El pack 26.3 acepta la entrada estable `slots3x3_depth_v1`: ocho bancos de
dieciséis fuentes, RGB de cuatro bits y radio logarítmico de 0.25 a 8 bloques.
El modelo sigue siendo `strobelights:carrier/rgb`, con tinte custom-model-data
en el índice cero. El API `stableSourceProtocol()` anuncia esta capacidad en
26.3 sin consultar el perfil gráfico de TRP.

El transporte interno es `frame_slots_5x7_v1`. Los 64 slots nativos permanecen
independientes; los 128 de integración usan los slots 64..191 de pantalla,
en dos filas adicionales de siete píxeles. Se extraen del payload, porque
XmasLights usa brillo 15/15 para todas sus fuentes. Las coordenadas conservan
su posición CPU real y se capturan con reversed-Z.

Los ocho lotes nativos conservan su composición. Dieciséis lotes adicionales
acumulan la integración en dos targets alternos a 640×360, codificando el rango
HDR en RGB/alpha. La composición utiliza diez samplers, por debajo del mínimo
OpenGL de dieciséis. No se crean strobes persistentes, círculos ni TextDisplay.

## Verificación de esta entrega

- **583 pruebas Java** de StrobeLights en las siete APIs, sin fallos ni omisiones;
  verificación de los cinco JAR y sus packs por familia.
- **232 pruebas Java** de XmasLights, incluyendo un pack real de StrobeLights
  0.10.43 para 1.21.4, aceptación por contrato y rechazo de un pack sin ABI.
- **22 variantes GLSL** compiladas y enlazadas, incluidos glint, OIT y ambas
  convenciones de profundidad.
- **38 comprobaciones GPU** con los shaders reales en OpenGL/NVIDIA: fuentes
  nativas, cielo, bancos y límites de slots de XmasLights, rojo/verde/azul/magenta,
  y rojo nativo más azul de XmasLights simultáneos sin colisión.
- **Paper 26.3 real, aislado**, con ambos JAR finales: pack descargado y SHA-1
  verificado por un cliente de protocolo; siete carriers recibidos, fuentes sobre
  suelo y pared, cero TextDisplay. XmasLights registra que utiliza el renderer
  RGB de StrobeLights 0.10.43 y no registra strobes.

Evidencia local conservada fuera del árbol versionado:

```text
../backups/xmas-strobe-final-check.log
../backups/xmas-2.9.2-build.log
../backups/xmas-frame-gpu.log
../backups/xmas-strobe263-verified-pair.log
A:/MrDino Dev Cave/AstralicaStudio/XmasLights/.tools/xmas-strobe263-292-01043-verified/
```

El bot disponible no implementa aún el protocolo 26.3. Esa prueba de servidor
empleó un bot 1.21.4 traducido por ViaVersion/ViaBackwards 5.12.2, exclusivamente
en el servidor aislado. Comprueba la entrega del pack 26.3 y las entidades reales,
no la ejecución gráfica en un cliente 1.21.4. Los shaders de 26.3 se prueban
por separado en GPU. Esos plugins de traducción **no son un requisito de uso**.
No se ha realizado una sesión visual de Minecraft 26.3 ni una validación completa
con OptiFine, Iris, Sodium, TRP o Nexo; sigue pendiente medir aspecto/rendimiento
de las cadenas densas dentro del juego.

## Instalación

Detener Paper, sustituir **los dos JAR**, conservando las carpetas de datos, e
iniciar. Para 26.3 usar `StrobeLights-0.10.43+mc.v.26.3.X.jar` y
`XmasLights-2.9.2+mc.v.26.3.X.jar`. No dejar las copias anteriores en `plugins/`.
Aceptar el nuevo pack de StrobeLights al entrar: su hash cambia con los shaders.
Las luces guardadas se conservan. Los cinco exports de StrobeLights mantienen
sus rangos separados; no se genera un JAR conjunto `26.X`.
