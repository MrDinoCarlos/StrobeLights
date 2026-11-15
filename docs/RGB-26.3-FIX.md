# Correcciones del RGB en 26.3 — 0.10.42 a 0.10.44

Las correcciones de profundidad y posición descritas aquí eran necesarias, pero
las pruebas GPU iniciales omitían el horneado del modelo de Minecraft. 0.10.44
corrige ese fallo adicional y verifica el color en un cliente real; ver
[el modelo y las mediciones](CARRIER-26.3-FIX.md).

La luz guardada como `#FF00FF` aparecía blanca: únicamente se veía el bloque
LIGHT de respaldo. Se comprobó que el cliente había recibido el ZIP de 26.3
correcto y que el servidor conservaba el magenta configurado.

El fallo estaba en la profundidad del transporte. El cliente oficial 26.3
limpia la profundidad a **cero** y compara con **GREATER**. El port escribía
los paquetes cerca de cero, detrás del terreno. Compilar y enlazar GLSL no
detectaba ese error de ejecución.

La corrección coloca los paquetes en profundidad **uno**, tanto con clip
`[0,1]` como `[-1,1]`. El postprocesado identifica el cielo por profundidad
cero y conserva la reconstrucción usando los coeficientes reales de proyección.
Los pipelines de las versiones anteriores no cambian.

También se corrigió la posición: `VertexConsumer.putBakedQuad` aplica el pose
del item en la CPU. El shader debe usar `Position`, no el centro literal
`[0.5,0.5,0.5]`. La solución inicial de usar un elemento puntual era incorrecta:
`UnbakedCuboidGeometry` lo descarta y el item queda sin caras. Desde 0.10.44 el
modelo contiene una cara válida; la escala HEAD cero la reduce al punto de la
fuente en la CPU, después del horneado. Así todos los fragmentos transportan
exactamente la misma posición, sin mezclar bits de vértices distintos.

## Comprobación ejecutable

`scripts/gpu/FrameRendererGpuTest.java` crea un contexto OpenGL oculto y ejecuta
los shaders reales: captura del item, lotes de iluminación y composición.
Coloca una superficie delante del punto de transporte y aplica la misma
comparación de profundidad que Minecraft 26.3.

Con el código anterior, la prueba fallaba: el píxel de cabecera seguía siendo
el gris del terreno `[31,31,31]`. Con la corrección, el magenta produce
`[104,16,104]`; rojo, verde y azul aumentan únicamente el canal correspondiente.
También se comprueba que el cielo no recibe iluminación. Se ejecutan los cinco
casos en ambas convenciones de clip.
La prueba verifica además la posición recibida: una fuente en `[0,0,-5]` debe
conservar esas coordenadas. Detectó el segundo error porque el transporte
anterior devolvía `[0.5,0.5,0.5]` aunque sus colores numéricos parecieran correctos.

```text
python scripts/validate_26_3_shaders.py --vanilla-dir build/vanilla-26.3 --download-vanilla --glslang <validador>
gradlew.bat gpuTest263
gradlew.bat build
```

La prueba local usa OpenGL y la GPU NVIDIA; GitHub ejecuta la misma prueba con
Mesa bajo Xvfb. Esto comprueba píxeles reales, pero no equivale a una sesión
visual completa de Minecraft ni a probar cada mod de renderizado.

## OptiFine y otros renderizadores

El carrier 26.3 es opaco y mantiene su firma al redimensionar el atlas, incluida
la preparación mediante SRC_OVER utilizada por OptiFine. Hay una prueba de
regresión para ese comportamiento. El pipeline antiguo de 1.21.4 conserva su
propia prueba de firma y redimensionamiento.

Un shaderpack externo que reemplace los shaders internos puede impedir el RGB.
[Iris documenta que ignora los core shaders de los resource packs cuando hay
un shaderpack activo](https://github.com/IrisShaders/Iris/blob/26.1/docs/development/compatibility/core-shaders.md).
Esa combinación necesita integración con el shaderpack o un componente de
cliente; el plugin Paper por sí solo no puede garantizarla. No se ha ejecutado
una prueba dentro de OptiFine, Iris o Sodium durante esta corrección.

## Actualización

Los cinco exports mantienen sus rangos separados y pasan a 0.10.44. Sustituir
el JAR antiguo del servidor por el de su rango y reiniciar; el nuevo contenido
del pack cambia el SHA-1 y se vuelve a descargar. No dejar ambos JAR en plugins.
La configuración de las luces y los datos guardados se conservan.
