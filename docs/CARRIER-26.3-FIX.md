# 0.10.44 — el modelo de captura quedaba sin caras en Minecraft

El cliente aceptaba el pack 0.10.43 y ejecutaba `minecraft:end_of_frame`, pero
la luz guardada en rojo no aparecía. Las pruebas anteriores verificaban GLSL,
profundidad, payload y composición; inyectaban directamente un atributo Position
constante y por ello no comprobaban el horneado del modelo por Minecraft.

La reproducción con **Minecraft vanilla 26.3 real y Paper 26.3-223 aislado**
confirmó estas tres condiciones simultáneas:

- El ItemDisplay recibía el color rojo correcto y tenía `noCulling=true`.
- El cliente tenía el postprocesado aplicado, sin efectos fallidos.
- El modelo `strobelights:carrier/rgb` terminaba como
  `ItemQuads[all=[], solid=[], translucent=[]]`: ninguna cara llegaba al shader.

`UnbakedCuboidGeometry.bake` del cliente oficial elimina las caras cuyo tamaño
en cualquiera de sus dos ejes es cero. El elemento puntual `[8,8,8]..[8,8,8]`
era eliminado antes de que el vertex shader pudiera expandirlo en pantalla.

## Corrección

El modelo 26.3 ahora tiene una cara UP válida en un elemento de tamaño 16.
La transformación de visualización **HEAD usa escala `[0,0,0]`**: después del
horneado, Minecraft reduce los cuatro vértices a la posición de la fuente en
la CPU. El shader recibe cuatro posiciones idénticas y puede expandir el quad
para transportar los bits sin mezclarlos entre triángulos.

Se conserva la codificación, la profundidad reversed-Z y la separación de
64 slots nativos y 128 de integración. XmasLights 2.9.2 también utiliza HEAD y
el mismo modelo de StrobeLights; su protocolo no cambia. Los modelos y shaders
de las versiones anteriores a 26.3 no se modifican, salvo la revisión del pack.

## Mediciones en el cliente real

Cliente vanilla 26.3, OpenGL, NVIDIA RTX 3060, framebuffer 640×360, sobre una
superficie blanca de noche. Un sondeo temporal del cliente leyó únicamente los
datos de captura y un píxel de la escena antes/después del efecto mediante la
API de readback de RenderPearl. Se usaron una instancia y un servidor de pruebas,
sin modificar el mundo ni la configuración del servidor del usuario.

Con 0.10.43 había cero paquetes válidos y el píxel permanecía en `[25,26,47]`.
Con el JAR final de 0.10.44 se capturaron 56 observaciones con paquete válido:

| Color | Transparencia mejorada activada | Desactivada |
| --- | --- | --- |
| Rojo | `[83,16,28]` | `[83,16,28]` |
| Verde | `[15,86,28]` | `[15,86,28]` |
| Azul | `[15,16,115]` | `[15,16,115]` |
| Magenta | `[83,16,116]` | Sin ensayo separado |

Los valores son el color final de una superficie iluminada con el ambiente de
la escena, no una promesa de que cada píxel coincida con el RGB puro de la luz.
El resumen, los hashes del JAR y del pack están en
[la evidencia JSON](verification/carrier-26.3-0.10.44.json). Los registros completos
se conservan localmente en `../backups/carrier-26.3-0.10.44/`.

También pasan 583 pruebas Java en siete APIs y 38 casos GPU. La regresión GPU
ahora lee el modelo real, rechaza una cara que Minecraft eliminaría y envía un
buffer de posiciones obtenido de su transformación HEAD; conserva los casos de
todos los bancos de XmasLights y de fuentes nativas/integración simultáneas.
La prueba de contrato comprueba por separado que exista una cara horneable y
que sus cuatro posiciones transformadas coincidan exactamente.

## Alcance de compatibilidad

| Configuración | Comprobación realizada |
| --- | --- |
| Vanilla 26.3 / OpenGL, transparencia mejorada activada y desactivada | Ejecución del cliente real y mediciones anteriores |
| Shaders 26.3, ambas convenciones de profundidad y fases OIT/glint | 22 variantes GLSL; 38 casos GPU para captura/composición |
| XmasLights 2.9.2 | Misma ABI y modelo HEAD; integración de servidor previamente verificada y regresiones GPU de sus fuentes |
| OptiFine, Sodium, Iris o Vulkan en el cliente | Sin ejecución específica en esta corrección |
| Shaderpacks externos activos | Requieren integración con el renderizador/pack concreto |

Un plugin Paper con resource pack no puede garantizar RGB con cualquier
shaderpack: [Iris documenta que sustituye los core shaders de los packs de
recursos al activar un shaderpack](https://github.com/IrisShaders/Iris/blob/26.1/docs/development/compatibility/core-shaders.md).
El aviso de pack aceptado no demuestra que un renderizador externo ejecute
estos shaders. Una integración o componente de cliente debe tratarse y probarse
de forma específica; no se certifica compatibilidad universal a partir de la
prueba vanilla.

## Instalación

Sustituir StrobeLights por el JAR **0.10.44 de la familia correcta**, reiniciar
Paper y aceptar el pack nuevo. Conservar los datos y XmasLights 2.9.2, si ya está
instalado. Los exports siguen siendo cinco JAR separados, incluidos 26.1.X,
26.2.X y 26.3.X.
