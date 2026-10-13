# Registro de cambios de MeTube

## 2.8.0 — 2026-10-13 — Pestaña y filtros de búsqueda, Shorts a pantalla completa, minirreproductor flotante

### Novedades

- **La búsqueda tiene su propia pestaña.** La búsqueda está ahora en la barra inferior, donde estaba Suscripciones (Suscripciones sigue en el panel Tú), y el icono de búsqueda deja la barra superior.
- **Vuelven los filtros de búsqueda.** El botón junto a la barra de búsqueda filtra los resultados por fecha de subida (de la última hora a este año), duración, tipo (vídeos, canales, listas, películas) y características (en directo, 4K, HDR), y los ordena por fecha, visualizaciones o valoración.
- **El minirreproductor flota al salir de la app.** Si vas a Inicio mientras el minirreproductor suena en Inicio, se abre una ventana flotante. Al ampliarla vuelves a la app; al cerrarla se detiene el vídeo.
- **Los Shorts llenan la pantalla de borde a borde.** Un desenfoque ambiental ocupa las franjas bajo las barras de estado y de navegación, y los bordes del vídeo se funden con él.
- **Una barra de búsqueda de cristal que brilla al escribir.** Los resultados pasan bajo una barra esmerilada con borrar y búsqueda por voz dentro. Lo que escribes brilla con los colores de Siri, y al abrir la búsqueda se iluminan los bordes de la pantalla.
- **Un corazón que late al abrir.** El corazón late una vez en la pantalla de inicio (Android 12 o posterior) y en la barra superior.

### Cambios

- **Los Shorts se pausan al salir de la app.** Los Shorts ya no abren la ventana flotante, no suenan en segundo plano ni pasan al minirreproductor; al volver siguen reproduciéndose.

### Correcciones

- **Los Shorts ya no se convierten en vídeos normales al azar.** Un Short podía perder la pista del feed de Shorts y abrirse en la página del vídeo, y el final de los Shorts cargados pasaba a un vídeo siguiente normal.
- **La ventana flotante se llena con vídeos verticales y cuadrados.** Mostraba el vídeo en la mitad superior sobre negro, con el botón de pantalla completa debajo.
- **La pantalla se apaga cuando un vídeo termina o se pausa.** Antes seguía encendida hasta agotar la batería.
- **La pista de audio que eliges vale solo para ese vídeo.** Un doblaje elegido una vez ya no te sigue a todos los vídeos doblados, y "en" ya no coincide con "ben".
- **Las descargas de vídeos doblados conservan el audio original,** también tras reintentar.
- **Los títulos originales funcionan, con o sin DeArrow.**
- **Las copias de seguridad dejan fuera tus credenciales.** La copia de Android y el archivo de copia de la app se restauran sin sesión iniciada.
- **La pantalla completa funciona en tabletas,** donde Android 16 ignora las peticiones de giro.
- **Sin mosaico de Shorts vacío en los canales ni acciones de canal en las tarjetas de listas.**

Las siete últimas correcciones vienen de NewTube 1.15.1.

## 2.5.7 — 2026-10-09 — El botón de Música abre tu app de música

### Correcciones

- **El botón de Música abre la app que elegiste para los enlaces de YouTube Music.** Antes solo abría YouTube Music. Ahora sigue el ajuste "Abrir de forma predeterminada" de Android, así que funcionan apps como BitChord; sin app de música, se abre en el navegador.

## 2.5.6 — 2026-10-09 — Imagen en imagen siempre y botón de Música

### Novedades

- **Imagen en imagen siempre que salgas de un vídeo:** al ir a Inicio o cambiar de app, el vídeo pasa a una ventana flotante. El ajuste "Reproducir en segundo plano" desaparece; para solo audio, usa los auriculares.
- **Auriculares en la ventana flotante:** toca la ventana y luego los auriculares; la ventana se cierra y el sonido sigue.
- **Botón de Música:** en canciones y videoclips, abre el vídeo en YouTube Music. Solo aparece si YouTube Music lo tiene.

### Correcciones

- **Imagen en imagen más fluida:** MeTube ya no reenvía los botones de la ventana cada vez que el vídeo carga.

## 2.5.1 — 2026-10-09 — Solo audio, nuevo icono y correcciones

### Novedades

- **Solo audio:** toca los auriculares arriba a la izquierda del reproductor para escuchar sin vídeo. No se descarga el vídeo y, al pulsar Inicio, el sonido sigue sin abrir una ventana flotante.
- **Nuevo icono:** un corazón rojo con un botón de reproducir, sobre negro.

### Correcciones

- **Sin cierres al volver de la imagen en imagen en alta calidad:** la memoria de vídeo ahora tiene un límite.
- **Ajustes en pantalla completa:** los menús ya no muestran las barras del sistema ni un segundo panel.
- **Los registros de diagnóstico se llaman metube-diagnostics.**

## 2.5.0 — 2026-10-09 — Cristal y Shorts más rápidos

### Novedades

- **Cuenta atrás para el siguiente vídeo:** al terminar un vídeo, una tarjeta cuenta diez segundos. Reproducir ya, Volver a reproducir o Cancelar.
- **Un destello al dar me gusta o suscribirte.**
- **Barra superior de cristal flotante:** el logo y los botones de enviar y buscar flotan como dos píldoras de cristal líquido.
- **Una cita al tocar el logo de MeTube,** distinta en cada toque.
- **Menús esmerilados:** los menús del reproductor y de las tarjetas muestran un cristal esmerilado sin desenfocar la página.
- **Ajuste Cristal líquido:** desactívalo en Ajustes → General para un desenfoque simple que gasta menos batería.
- **Cifras en K y M,** también con el teléfono en inglés (India). Ajuste Cifras en lakh y crore para volver al formato indio.

### Cambios

- **Los Shorts pasan más rápido:** se adelanta el short siguiente al siguiente y una precarga a medias ya no se descarta.
- **Suscrito es una píldora de cristal;** el título y las visualizaciones son un solo botón; la barra de navegación rebota al cambiar de pestaña.
- **Las descargas van a Movies/MeTube y Music/MeTube.**

## 2.0.2 — 2026-10-08 — Pantalla completa vertical

### Novedades

- **Pantalla completa vertical:** desliza la página hacia abajo desde el título y el vídeo ocupa toda la pantalla en vertical, con colores ambiente en las franjas negras.
- **Botón flotante de pantalla completa** de cristal, abajo a la derecha.
- **Nueva animación de carga** con la galleta del icono.

### Cambios

- **Los Shorts empiezan antes:** se precargan los primeros segundos del siguiente short mientras ves el actual.
- Barra de progreso más gruesa al arrastrarla; botón de minimizar redondo en pantalla completa; sin títulos de página en la barra superior.
- Los comentarios de los Shorts ya no muestran el vídeo como uno normal al deslizarlos hacia abajo.

## 2.0.1 — 2026-10-07 — Tacto

### Novedades

- **Vibración en cada toque.** Botones, tarjetas, interruptores, menús y hojas responden con un clic suave, y también cada salto con doble toque. Sigue el ajuste de vibración del teléfono.
- **Negro puro para AMOLED.** En modo oscuro las páginas son negro puro y ahorran batería.
- **Shorts que se deslizan como en YouTube.** La imagen del siguiente short entra bajo tu dedo y se queda hasta que empieza el vídeo.
- **Barra de progreso arrastrable en Shorts**, botón de más opciones, disco giratorio y título que se expande al tocarlo.

### Cambios

- Los Shorts vistos se recuerdan 7 días, también tras reiniciar.
- La línea de progreso de los Shorts ya avanza.

## 2.0.0 — 2026-10-07 — Liquid Glass

### Novedades

- **Shorts como en YouTube.** La pestaña Shorts abre directamente el reproductor a pantalla completa. Toca para pausar, toca dos veces para dar me gusta, mantén pulsado para 2x.
- **Shorts sin fin y sin repetir.** La siguiente página se carga antes de llegar al final y nunca se repite un short que ya viste.
- **Comentarios en Shorts.** Los comentarios se abren debajo del short, que se encoge arriba.
- **Cristal líquido.** La barra flotante curva lo que hay debajo como una lente; la barra superior también es de cristal.
- **Un icono nuevo**, minimalista, al estilo Material 3.

## 1.5.0 — 2026-10-07 — Shorts

### Novedades

- **Shorts.** Una pestaña Shorts con tarjetas verticales. Toca una y se reproduce a pantalla completa; desliza hacia arriba para el siguiente y hacia abajo para el anterior. Cada short se repite hasta que deslizas, y el siguiente se carga mientras lo ves.
- **Brillo ambiental.** En la página del vídeo, los colores del vídeo se extienden bajo el reproductor y se funden con la página.
- **Un icono nuevo de MeTube.** Una M redondeada sobre un fondo coral y magenta, con una burbuja de cristal.

### Cambios

- **Toca el título para leer más.** Ya no hay flecha: toca el título o la línea de visualizaciones para abrir la descripción.
- **Todo redondeado.** Menús, hojas, diálogos y avisos tienen esquinas redondeadas.

## 1.0.0 — 2026-10-07 — Floating Glass

### Novedades

- **Cristal flotante.** La barra de navegación inferior es ahora una cápsula de cristal esmerilado que flota sobre la página y difumina en vivo lo que pasa por debajo. En Android 13 y posteriores su borde curva el contenido como una lente.
- **Un aspecto nuevo.** Una página más oscura, un acento rojo, títulos grandes, miniaturas redondeadas y botones y tarjetas de cristal en la página del vídeo.
- **MeTube.** Un nombre y un id de app nuevos, así que se instala junto a NewTube. Las actualizaciones llegan desde github.com/yashoncode/metube.
