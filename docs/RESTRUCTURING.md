# Estructura unificada y compatibilidad

## Situación encontrada

Había seis worktrees y seis ramas con copias completas de StrobeLights. La
carpeta `paper-1.21.4` estaba en `master`, con cambios locales de 0.10.41;
las otras ramas seguían en 0.10.37. No existía un build que comprobara todas
las variantes juntas. Los nombres de JAR y ZIP estaban ligados a cada parche.

`compatibility/origins.json` conserva los commits de las ramas importadas.
Los worktrees antiguos no se borran ni se modifican: quedan como referencias.
El desarrollo continúa en `main`, desde este proyecto unificado.

## Distribución

| JAR | Versiones mantenidas | Java | API mínima del descriptor |
| --- | --- | --- | --- |
| `1.20.X` | 1.20.1 | 17 | 1.20 |
| `1.21.X` | 1.21.4, 1.21.10, 1.21.11 | 21 | 1.21.4 |
| `26.X` | 26.1.2, 26.2, 26.3 | 25 | 26.1 |

El sufijo agrupa los archivos de distribución. No implica compatibilidad con
parches que no estaban mantenidos, como 1.20.6 o 1.21.5, ni con futuras versiones.
El plugin comprueba la versión antes de iniciar servicios y verifica que el JAR
contenga su ZIP. Una versión no admitida recibe un error explícito.

El ZIP elegido depende de la versión del servidor; los clientes deben usar el
mismo formato. No se añade selección de packs por protocolo de ViaVersion.

## Código y recursos

- `src/main`: código, configuración e idiomas compartidos.
- `compatibility/legacy/java`: seis adaptaciones de API para 1.20.1, conservando
  sus modelos, el uso de brillo para transmitir datos y el transporte por SHA-1.
- `resource-pack`: pack base de 1.21.4, incluidos sus cambios locales de 0.10.41.
- `compatibility/modern/resource-pack`: archivos idénticos compartidos por las
  variantes posteriores que conservan el pipeline Fabulous.
- `compatibility/<versión>/resource-pack`: solamente las diferencias de esa ABI.
- `compatibility/<versión>/pack-excludes.txt`: archivos del pack base que no
  deben entrar en esa variante. Evita mezclar shaders de distintas generaciones.
- `compatibility/<versión>/tests`: contratos de los packs de las ramas originales.
- `gradle/legacy-carriers.gradle.kts`: generador original de los modelos de 1.20.1.

El build genera las fuentes efectivas de 1.20.1 y los packs combinados bajo
`build/`. Estos directorios no se editan ni se versionan. Las pruebas usan una
carpeta aislada por versión con sus fuentes y recursos efectivos.

1.21.11 conserva su carrier en la posición real de la luz. El resto mantiene
el carrier privado anclado al chunk del espectador. La disponibilidad del pack
y la preferencia gráfica de TRP siguen siendo decisiones independientes.

La confirmación del pack al entrar se comparte entre variantes. En APIs modernas
se registra cada UUID; la implementación antigua utiliza el SHA-1 disponible
en 1.20.1. Un fallo de otro pack no borra una confirmación ya recibida.

## Port RGB de 26.3

[Minecraft 26.3](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3)
eliminó `transparency.json`, cambió los shaders a `#include` con ubicaciones
explícitas y añadió el efecto permanente `end_of_frame`. Por eso no basta con
cambiar `api-version` o `pack.mcmeta`.

El port incorpora captura en `core/item`, respetando el código vanilla para
objetos normales y las fases OIT. Cada espectador dispone de 64 slots sin
colisiones; los tokens de sombras también ocupan uno. Los slots se reutilizan
cuando desaparecen las entidades y se limpian al desconectar.

Los paquetes transportan posición y proyección como bits IEEE-754, además del
RGB, tamaño y flash. El postprocesado reconstruye la posición en espacio de
cámara a partir de la profundidad real. Ocho lotes de ocho carriers calculan
la iluminación a 640×360 y la composición añade el flash direccional existente.
No depende de activar Fabulous en 26.3. La preferencia de TRP puede seguir
deshabilitando RGB para un usuario.

Limitaciones del port que deben revisarse dentro del juego:

- Estado experimental hasta validar aspecto y rendimiento en un cliente 26.3.
- Los siete píxeles inferiores de cada slot activo se reconstruyen desde la fila
  contigua; puede perderse detalle fino en ese borde, antes de dibujar el HUD.
- Resolución mínima del framebuffer: 320×8. Capacidad: 64 carriers por usuario,
  incluidos tokens de sombras y flashes; los excedentes conservan el fallback vanilla.
- Las sombras son aproximaciones de pantalla y la nueva composición necesita
  pruebas visuales de agua, cristal, zoom, movimiento y luces fuera de pantalla.
- Los packs que sustituyan `end_of_frame` o `core/item` necesitan una composición
  compatible. La integración real con TRP/Nexo en 26.3 requiere sus versiones
  correspondientes; las pruebas de ZIP no equivalen a arrancar esos plugins.

## Comprobaciones y comandos

```text
gradlew.bat clean build
```

Ejecuta las suites de siete APIs y comprueba que los tres JAR contienen
únicamente los ZIP de su familia, el descriptor correcto y bytecode de Java
17/21/25. `build/exports` se sincroniza: solo conserva los tres exports actuales.
No instala nada en un servidor ni crea una release pública.

```text
gradlew.bat exportResourcePack
```

Exporta ZIP sueltos solo cuando se piden. No son necesarios para instalar el JAR.

```text
python scripts/validate_26_3_shaders.py --vanilla-dir build/vanilla-26.3 --download-vanilla --glslang <ruta-al-validador>
```

Descarga el cliente oficial con SHA-1 verificado y compila/enlaza 22 variantes
de shaders con glslang. Comprueba las dos convenciones de profundidad, glint,
cutout y las fases OIT. La validación GLSL está en un job separado de GitHub.

Durante la migración pasaron 573 pruebas Java y 22 variantes GLSL. La comparación
de packs anteriores no encontró diferencias fuera de la revisión de metadatos y la normalización
de espacios finales;
1.20.1 añade los mismos 1.025 archivos que generaba su build original.
El propio `PostChainConfig` del cliente oficial 26.3 también aceptó el grafo
de diez pases. Esto no certifica el renderizado real de un cliente ni una instalación de TRP,
Nexo u OptiFine. No se ha realizado una prueba visual en Minecraft 26.3.
