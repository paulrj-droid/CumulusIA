package com.paulrj.cumulusia.ai

/**
 * 😄 COLECCIÓN DE CHISTES DE CUMULUS (modo «Buen humor y chistes»).
 *
 * Los modelos pequeños del teléfono repiten el mismo chiste, inventan chistes sin gracia y no se ríen.
 * Por eso la APP trae su propia colección (chistes de cada país, payas chilenas, adivinanzas, trabalenguas,
 * colmos, «¿qué le dijo…?» y «toc toc»), elige uno que todavía NO te haya contado, lo presenta con gracia
 * y se ríe al final. Humor sano y para todo público: chistes DE cada país, nunca burlas de su gente.
 * Si pides un tema que no está en la colección (ej. «chiste de dinosaurios»), responde la IA (con internet si está encendido).
 */
object JokeBank {

    enum class Kind(val label: String, val flag: String) {
        CHILE("Un chiste chileno", "🇨🇱"),
        ARGENTINA("Un chiste argentino", "🇦🇷"),
        MEXICO("Un chiste mexicano", "🇲🇽"),
        USA("Un chiste de Estados Unidos", "🇺🇸"),
        CHINA("Humor de China", "🇨🇳"),
        JAPON("Un chiste japonés (dajare)", "🇯🇵"),
        COREA("Un chiste coreano (ajae gag)", "🇰🇷"),
        PORTUGAL("Un chiste portugués", "🇵🇹"),
        BRASIL("Un chiste brasileño (piada)", "🇧🇷"),
        PAYA("Una paya chilena", "🎸"),
        ADIVINANZA("Una adivinanza", "🤔"),
        TRABALENGUAS("Un trabalenguas", "👅"),
        COLMO("¿Cuál es el colmo…?", "😆"),
        QUE_LE_DIJO("¿Qué le dijo…?", "💬"),
        TOCTOC("Toc, toc", "🚪"),
        PAREJAS("Un chiste de parejas (para grandes)", "💑"),
        GENERAL("Un chiste", "😄")
    }

    /** [text] el chiste · [answer] la respuesta (adivinanzas) · [note] explicación del juego de palabras. */
    class Joke(val kind: Kind, val text: String, val answer: String = "", val note: String = "") {
        val id: String get() = kind.name + "_" + text.hashCode().toUInt().toString(36)
    }

    private fun j(kind: Kind, text: String, answer: String = "", note: String = "") = Joke(kind, text, answer, note)

    val ALL: List<Joke> = listOf(
        // 🇨🇱 CHILE
        j(Kind.CHILE, "Un turista le pregunta a un santiaguino:\n—¿Dónde pasa la micro?\n—Ahí al tiro.\n—¿Y cuánto es «al tiro»?\n—Depende del chofer, po."),
        j(Kind.CHILE, "En Chile no hay temblores chicos: si no se cae nada, decimos «ah, tembló no más»… ¡y seguimos tomando once!"),
        j(Kind.CHILE, "—Mamá, ¿puedo ir a la fonda?\n—Sí, pero vuelves temprano.\n—¿Temprano como a qué hora?\n—Antes de que se acabe septiembre."),
        j(Kind.CHILE, "—Mamá, en el colegio me dicen «el cachai».\n—¿Y por qué, hijo?\n—¿Cachai?"),
        j(Kind.CHILE, "¿Qué le dijo una empanada de pino a otra el 17 de septiembre?\n—Aprovechemos ahora… ¡que en octubre nadie nos pesca!"),
        j(Kind.CHILE, "¿Qué le dijo el pan amasado a la marraqueta?\n—¡Tanto que te las dai de francesa, si naciste en la panadería de la esquina!"),
        j(Kind.CHILE, "—Oye, ¿queda muy lejos el supermercado?\n—No, es aquí no más, cerquita.\nTres cuadras, dos subidas y media hora después… ¡seguía siendo «aquí no más»!"),
        j(Kind.CHILE, "—Garzón, ¿esta cazuela tiene pollo?\n—Sí, caballero.\n—¿Y dónde está?\n—Ah, es que el pollo pasó corriendo por la olla, pero le dejó harto cariño."),
        j(Kind.CHILE, "Un chileno nunca dice «no»: dice «ya, veamos», «puede ser», «yo te aviso»… y tú ya sabes que es no."),
        j(Kind.CHILE, "—Compadre, ¿me prestas tu paraguas?\n—¿Pa' qué, si no está lloviendo?\n—Es que en Santiago, si no lo llevo, ¡seguro llueve!"),
        j(Kind.CHILE, "¿Por qué el completo italiano siempre anda contento?\n¡Porque anda con palta, tomate y mayo… puro buena onda!"),
        j(Kind.CHILE, "—Profe, ¿me puedo ir antes?\n—¿Y por qué?\n—Porque mi abuelita hizo sopaipillas y está lloviendo… ¡es una emergencia nacional!"),

        // 🇦🇷 ARGENTINA
        j(Kind.ARGENTINA, "Un tipo entra a una heladería:\n—¿Tenés dulce de leche?\n—Sí.\n—¿Y dulce de leche granizado?\n—Sí.\n—¿Y dulce de leche con brownie?\n—Sí.\n—Bueno… dame de frutilla."),
        j(Kind.ARGENTINA, "¿Qué le dijo el mate a la bombilla?\n—Sin vos no soy nada… ¡pero con vos me vacían cada cinco minutos!"),
        j(Kind.ARGENTINA, "—Che, ¿venís al asado del domingo?\n—¿A qué hora?\n—A la una.\n—Perfecto, llego a las tres y todavía no prendieron el fuego."),
        j(Kind.ARGENTINA, "—Mamá, ¿puedo ver el partido?\n—Primero hacé la tarea.\n—Pero ma, la tarea es de geografía… ¡y el partido es en el estadio, eso es geografía!"),
        j(Kind.ARGENTINA, "—Che, ¿me pasás la sal?\n—Tomá.\n—Esto es azúcar.\n—Y bueno, che, es lo que había… ¡ponele onda al asado dulce!"),
        j(Kind.ARGENTINA, "Un porteño le pregunta a otro:\n—¿Cómo hacés para llegar siempre a tiempo?\n—Fácil: digo que llego en cinco minutos… y salgo de casa una hora antes."),
        j(Kind.ARGENTINA, "—Mozo, ¿esta medialuna es de hoy?\n—Sí, señor, de hoy.\n—¿Y la de ayer?\n—También era de hoy… ayer."),
        j(Kind.ARGENTINA, "—¿Sabés por qué el dulce de leche nunca está triste?\n—¿Por qué?\n—¡Porque siempre está re-dulce!"),

        // 🇲🇽 MÉXICO
        j(Kind.MEXICO, "La maestra pregunta:\n—Pepito, ¿qué está más lejos, la Luna o Guadalajara?\n—Guadalajara, maestra.\n—¿Por qué?\n—Porque la Luna sí la vemos desde aquí."),
        j(Kind.MEXICO, "—Pepito, conjuga el verbo caminar.\n—Yo camino, tú caminas, él camina…\n—Más rápido, Pepito.\n—¡Nosotros corremos, ustedes corren, ellos corren!"),
        j(Kind.MEXICO, "¿Qué le dijo un nopal a otro nopal?\n—¡No me espines, güey!"),
        j(Kind.MEXICO, "¿Qué le dijo un aguacate a otro?\n—¡Juntos somos guacamole!"),
        j(Kind.MEXICO, "—Mamá, ¿me das dinero para el camión?\n—¿Y para qué quieres un camión, mijo?\n—¡Para ir a la escuela, ma!", note = "En México, «camión» es el bus o la micro."),
        j(Kind.MEXICO, "¿Cómo se despide un químico mexicano?\n—¡Ácido un placer!"),
        j(Kind.MEXICO, "—¿Por qué los tacos nunca se pelean?\n—Porque siempre están bien envueltos… ¡y con harta salsa de la buena onda, güey!"),
        j(Kind.MEXICO, "—Pepito, ¿cuántos dedos tienes en la mano?\n—Cinco, maestra.\n—¿Y en las dos manos?\n—Diez.\n—¿Y en los pies?\n—¡Órale, maestra, eso ya es mucha tarea!"),

        // 🇺🇸 ESTADOS UNIDOS
        j(Kind.USA, "¿Por qué el esqueleto no fue a la fiesta?\n¡Porque no tenía cuerpo para ir!", note = "En inglés: «he had no body to go with» (no tenía a nadie / no tenía cuerpo)."),
        j(Kind.USA, "No confíes en los átomos…\n¡Lo inventan todo!", note = "En inglés: «they make up everything» (inventan todo / forman todo)."),
        j(Kind.USA, "¿Por qué las aves vuelan al sur en invierno?\nPorque es muy lejos para ir caminando."),
        j(Kind.USA, "Le pregunté a mi perro cuánto es dos menos dos.\nNo dijo nada."),
        j(Kind.USA, "¿Qué le dijo el océano a la playa?\nNada… ¡solo la saludó con una ola!", note = "En inglés: «it just waved» (saludó con la mano / hizo una ola)."),
        j(Kind.USA, "¿Por qué el espantapájaros ganó un premio?\n¡Porque era sobresaliente en su campo!", note = "En inglés: «outstanding in his field»."),
        // 🇺🇸 Agregados por el usuario (tal como los pasó)
        j(Kind.USA, "Un policía detiene a un conductor en Florida por ir serpenteando en la carretera. Al acercarse al auto, ve a un loro sentado en el asiento del copiloto con una cerveza en la pata.\nEl policía le dice al chofer:\n—Caballero, ¿ha estado bebiendo mientras maneja?\nEl tipo, nervioso, responde:\n—¡No, oficial, para nada! ¡Se lo juro por mi vida!\nEn eso, el loro lo mira, le da un sorbo a la lata y grita:\n—¡No le crea, oficial! ¡Lleva tres cervezas en el maletero, dos tequila bajo el asiento y más encima yo vengo borracho porque me convidó a mí también!"),
        j(Kind.USA, "Un matrimonio maduro de Texas está celebrando sus 40 años de casados en su enorme rancho. Están sentados en el pórtico mirando el atardecer y tomando té helado.\nLa Sra. Betty suspira, mira a su esposo John y le dice con nostalgia:\n—John... cuando nos casamos, me comprabas vestidos elegantes, me llevabas a bailar a la ciudad y me tomabas de la mano a cada rato. Ahora te pasas el día mirando el partido de fútbol americano o arreglando el tractor. ¿Dónde quedó toda esa pasión?\nJohn da un sorbo a su té, la mira con calma y le responde:\n—Betty, cuando compré el camión hace treinta años, le puse gasolina y anduvo perfecto. No necesito ir a decirle al motor todos los días que me alegro de que siga funcionando."),
        j(Kind.USA, "Un policía de tránsito en una autopista de Texas ve pasar un auto a 180 km/h. Enciende las sirenas, acelera a toda velocidad y logra que el conductor se orille.\nEl policía se acerca a la ventana, se acomoda los lentes de sol y le dice al conductor con voz grave:\n—Señor, ¿sabe a qué velocidad venía? Hoy ha sido un día larguísimo, estoy cansado y mi turno termina en diez minutos. Si me da una excusa que nunca antes haya escuchado en mi vida, lo dejo ir sin multa.\nEl conductor lo piensa dos segundos, mira al oficial y le dice:\n—Mire, oficial... mi esposa se escapó la semana pasada con un policía de tránsito. Cuando lo vi venir por el espejo a toda velocidad con las luces encendidas... ¡pensé que me la venía a devolver!\nEl policía se queda en silencio, guarda la libreta de partes y le dice:\n—Que tenga un buen día, caballero. Siga su camino."),
        j(Kind.USA, "Un corredor de bolsa en Nueva York compra un loro importado superinteligente. Durante tres meses, el loro aprende a analizar el mercado financiero mirando la televisión todo el día.\nUna mañana, el loro empieza a gritar desde su jaula:\n—¡Vende las acciones de tecnología! ¡Compra petróleo! ¡Se viene la inflación! ¡Vende todo!\nEl tipo, asustado pero confiado en la inteligencia del pájaro, le hace caso y vende todas sus acciones. A la semana siguiente, la bolsa colapsa por completo y el hombre se salva de perder millones.\nLleno de gratitud, va a la jaula con una fruta exótica y le dice:\n—¡Eres un genio! Me salvaste de la quiebra. ¿Cómo supiste que el mercado se iba a caer?\nY el loro lo mira de lado y le susurra:\n—¡Yo no sabía nada, imbécil! ¡Estaba jugando a adivinar como hacen todos ustedes en Wall Street!"),
        j(Kind.USA, "Una nave espacial gigante aterriza en pleno centro de Nueva York, justo al lado de Times Square. Se abre la escotilla con humo, luces de colores y música dramática.\nSale un marciano pequeño, verde, con tres ojos y un micrófono en la mano, y grita:\n—¡Humanos! ¡Venimos a conquistar la Tierra!\nEn eso, sale un tipo corriendo de un puesto de comida rápida, le pasa un contrato y una pluma, y le dice:\n—¡Excelente! Firma aquí para la película, aquí para la camiseta oficial, y a las cuatro tienes entrevista con Jimmy Fallon. ¡No nos hagas perder tiempo, que el minuto de aire es carísimo!"),

        // 🚪 TOC TOC (knock-knock jokes)
        j(Kind.TOCTOC, "—Toc, toc.\n—¿Quién es?\n—Lechuga.\n—¿Lechuga quién?\n—¡Lechuga, ábreme que hace frío!", note = "En inglés: «Lettuce in» suena como «Let us in» (déjanos entrar)."),
        j(Kind.TOCTOC, "—Toc, toc.\n—¿Quién es?\n—La vaca interrumpidora.\n—La vaca interrumpidora qu…\n—¡MUUUUU!"),
        j(Kind.TOCTOC, "—Toc, toc.\n—¿Quién es?\n—Ya.\n—¿Ya quién?\n—¡Yahoo! ¡Qué contento estás de verme!", note = "En inglés: «Yah who?» suena como «Yahoo!»."),
        j(Kind.TOCTOC, "—Toc, toc.\n—¿Quién es?\n—Lola.\n—¿Lola quién?\n—¡Lo la-mento, me equivoqué de casa!"),
        j(Kind.TOCTOC, "—Toc, toc.\n—¿Quién es?\n—Teresa.\n—¿Teresa quién?\n—¡Te re-salvé de abrirle al vendedor de enciclopedias!"),

        // 🇨🇳 CHINA (humor tradicional y juegos de palabras)
        j(Kind.CHINA, "Un hombre cruzaba el río en bote y se le cayó su espada al agua. Rápido, hizo una marca en el bote: «Aquí se cayó».\nCuando el bote llegó a la orilla, se tiró al agua a buscarla… justo debajo de la marca.", note = "Es la fábula china «marcar el bote para buscar la espada» (刻舟求剑)."),
        j(Kind.CHINA, "Un campesino vio que un conejo chocó contra un árbol y quedó atontado. Feliz, dejó de trabajar y se sentó junto al árbol… a esperar que llegara el siguiente conejo.", note = "Fábula china «esperar junto al tronco» (守株待兔)."),
        j(Kind.CHINA, "Un campesino estaba impaciente porque su arroz crecía lento. Una noche tiró de cada brote un poquito para «ayudarlo».\nAl día siguiente le dijo a su hijo: «¡Hoy ayudé a crecer al arroz!»… y el arroz amaneció todo marchito.", note = "Fábula china «tirar de los brotes» (揠苗助长)."),
        j(Kind.CHINA, "En China nadie quiere vivir en el piso 4, pero todos quieren el piso 8.\n¿Por qué? Porque «cuatro» suena parecido a «muerte»… ¡y «ocho» suena parecido a «hacerse rico»!", note = "四 (sì) suena como 死 (sǐ), y 八 (bā) como 发 (fā)."),
        j(Kind.CHINA, "En el Año Nuevo chino nunca se comen todo el pescado.\n¿Por qué? Porque «pez» suena como «abundancia»… ¡y hay que dejar que sobre para todo el año!", note = "鱼 (yú, pez) suena como 余 (yú, sobrar)."),

        // 🇯🇵 JAPÓN (dajare: juegos de palabras)
        j(Kind.JAPON, "«Futon ga futtonda!»\n¡El futón salió volando!", note = "«Futon» (cama japonesa) y «futtonda» (salió volando) suenan casi igual."),
        j(Kind.JAPON, "«Arumi kan no ue ni aru mikan.»\nUna mandarina sobre una lata de aluminio.", note = "«Arumi kan» (lata de aluminio) y «aru mikan» (hay una mandarina) suenan igual."),
        j(Kind.JAPON, "En la pescadería:\n—«Ikura wa ikura desu ka?»\n¿Cuánto cuesta el ikura?", note = "«Ikura» son las huevas de salmón, y «ikura» también significa «¿cuánto?»."),
        j(Kind.JAPON, "«Neko ga nekoronda.»\nEl gato se echó a dormir.", note = "«Neko» (gato) y «nekoronda» (se echó) empiezan igual."),
        j(Kind.JAPON, "«Kaeru ga kaeru.»\nLa rana vuelve a casa.", note = "«Kaeru» significa «rana» y también «volver a casa»."),

        // 🇰🇷 COREA (ajae gags: chistes de tío)
        j(Kind.COREA, "¿Qué pasa si un rey se cae?\n¡King Kong!", note = "«King» es rey en inglés y «kong» (쿵) es el sonido de un golpe en coreano."),
        j(Kind.COREA, "¿Cómo se ríe una vaca en Corea?\n¡U-ja-ja-ja!", note = "«U» (우) es como suena «vaca» (소) al mugir, y «hahaha» es la risa: 우하하."),
        j(Kind.COREA, "¿Cuál es el rey más pobre del mundo?\n¡El sueldo mínimo!", note = "En coreano, «sueldo mínimo» es 최저임금 (choejeo-imgeum), e «imgeum» (임금) también significa «rey»."),
        j(Kind.COREA, "¿Qué le dice un pan a otro cuando se enoja?\n¡Ppang!", note = "«Ppang» (빵) significa pan… y también es el sonido de un estallido."),

        // 🇵🇹 PORTUGAL
        j(Kind.PORTUGAL, "Porque é que o livro de matemática anda triste?\nPorque tem muitos problemas!", note = "¿Por qué el libro de matemáticas anda triste? ¡Porque tiene muchos problemas!"),
        j(Kind.PORTUGAL, "Qual é o doce preferido dos fadistas?\nO pastel de nata… cantado com saudade!", note = "¿Cuál es el dulce favorito de los cantantes de fado? ¡El pastel de nata, cantado con nostalgia!"),
        j(Kind.PORTUGAL, "O que é que o bacalhau disse à batata?\n— Juntos somos o prato preferido de Portugal!", note = "¿Qué le dijo el bacalao a la papa? ¡Juntos somos el plato favorito de Portugal!"),

        // 🇧🇷 BRASIL (trocadilhos)
        j(Kind.BRASIL, "O que o pato disse para a pata?\n— Vem quá!", note = "¿Qué le dijo el pato a la pata? «Ven acá» (vem cá), con el «cuá» del pato."),
        j(Kind.BRASIL, "Por que a plantinha não foi atendida no hospital?\nPorque só tinha médico de plantão!", note = "«Plantão» es el turno del médico… y suena a «plantón» (planta grande)."),
        j(Kind.BRASIL, "Qual é o café mais perigoso do mundo?\nO ex-presso!", note = "«Expresso» (café) suena como «ex-preso» (ex presidiario)."),
        j(Kind.BRASIL, "O que o tomate foi fazer no banco?\nTirar extrato!", note = "«Extrato» es el estado de cuenta del banco… y también el concentrado de tomate."),

        // 🎸 PAYAS CHILENAS (cuartetas de 8 sílabas)
        j(Kind.PAYA, "Me pidieron una paya\ny aquí la vengo a cantar,\nsi la rima no me falla\n¡nadie me va a hacer callar!"),
        j(Kind.PAYA, "Una paya pa' la once\ncon sopaipilla y con té,\nsi me aplauden, me compongo,\ny si no… ¡igual canté!"),
        j(Kind.PAYA, "Soy Cumulus, payador\nde los cables y la red,\nno tengo guitarra, señor,\n¡pero la rima la sé!"),
        j(Kind.PAYA, "La empanada de pino\nle dijo a la de queso:\n«Tú serás muy fina,\npero a mí me dan más besos»."),
        j(Kind.PAYA, "Mi compadre fue a la playa\ncon bloqueador y quitasol,\nvolvió rojo como sandía…\n¡se quedó dormido al sol!"),
        j(Kind.PAYA, "En la fonda del dieciocho\nbailé cueca con mi tía,\nme pisó como diez veces\n¡y la culpa era mía!"),
        j(Kind.PAYA, "Un gallo cantó a las cuatro\npa' despertar al vecino,\ny el vecino, medio enojado,\n¡le cantó más fuerte al gallo, el muy pillo!"),

        // 🤔 ADIVINANZAS
        j(Kind.ADIVINANZA, "Blanca por dentro, verde por fuera.\nSi quieres que te lo diga, espera.", answer = "La pera"),
        j(Kind.ADIVINANZA, "Tiene dientes y no come,\ntiene barba y no es hombre.", answer = "El ajo"),
        j(Kind.ADIVINANZA, "Vuela sin alas, silba sin boca,\npega sin manos y no se toca.", answer = "El viento"),
        j(Kind.ADIVINANZA, "Cuanto más le quitas, más grande es.", answer = "El hoyo"),
        j(Kind.ADIVINANZA, "Tengo agujas y no sé coser,\ntengo números y no sé leer.", answer = "El reloj"),
        j(Kind.ADIVINANZA, "Agua pasa por mi casa,\ncate de mi corazón.", answer = "El aguacate (la palta)"),
        j(Kind.ADIVINANZA, "Oro parece, plata no es.\n¿Qué es?", answer = "El plátano"),

        // 👅 TRABALENGUAS
        j(Kind.TRABALENGUAS, "Tres tristes tigres tragaban trigo en un trigal."),
        j(Kind.TRABALENGUAS, "Pablito clavó un clavito.\n¿Qué clavito clavó Pablito?"),
        j(Kind.TRABALENGUAS, "El cielo está enladrillado,\n¿quién lo desenladrillará?\nEl desenladrillador que lo desenladrille,\nbuen desenladrillador será."),
        j(Kind.TRABALENGUAS, "Erre con erre cigarro,\nerre con erre barril,\nrápido ruedan los carros\ncargados de azúcar del ferrocarril."),
        j(Kind.TRABALENGUAS, "Compré pocas copas, pocas copas compré,\ny como compré pocas copas, pocas copas pagaré."),

        // 😆 COLMOS
        j(Kind.COLMO, "¿Cuál es el colmo de un jardinero?\n¡Que su polola lo deje plantado!"),
        j(Kind.COLMO, "¿Cuál es el colmo de un electricista?\n¡Que su señora se llame Luz y sus hijos le sigan la corriente!"),
        j(Kind.COLMO, "¿Cuál es el colmo de un astronauta?\n¡Quedarse sin espacio!"),
        j(Kind.COLMO, "¿Cuál es el colmo de un músico?\n¡Sacarse una mala nota!"),
        j(Kind.COLMO, "¿Cuál es el colmo de un relojero?\n¡No tener tiempo para nada!"),
        j(Kind.COLMO, "¿Cuál es el colmo de un matemático?\n¡Tener problemas en la casa!"),
        j(Kind.COLMO, "¿Cuál es el colmo de un pez?\n¡Ahogarse en un vaso de agua!"),
        j(Kind.COLMO, "¿Cuál es el colmo de un fotógrafo?\n¡Que lo revelen sus propios secretos!"),

        // 💬 ¿QUÉ LE DIJO…?
        j(Kind.QUE_LE_DIJO, "¿Qué le dijo un semáforo a otro?\n—¡No me mires, que me estoy cambiando!"),
        j(Kind.QUE_LE_DIJO, "¿Qué le dijo la luna al sol?\n—¡Tan grande y no te dejan salir de noche!"),
        j(Kind.QUE_LE_DIJO, "¿Qué le dijo un techo a otro?\n—¡Techo de menos!"),
        j(Kind.QUE_LE_DIJO, "¿Qué le dijo el 0 al 8?\n—¡Qué lindo cinturón!"),
        j(Kind.QUE_LE_DIJO, "¿Qué le dijo una impresora a otra?\n—¿Esa hoja es tuya o es impresión mía?"),
        j(Kind.QUE_LE_DIJO, "¿Qué le dijo una pared a otra?\n—Nos vemos en la esquina."),
        j(Kind.QUE_LE_DIJO, "¿Qué le dijo el café al azúcar?\n—Sin ti, mi vida es amarga."),
        j(Kind.QUE_LE_DIJO, "¿Qué le dijo un pez a otro?\n—Nada, nada."),
        j(Kind.QUE_LE_DIJO, "¿Qué le dijo una uva verde a una uva morada?\n—¡Respira, respira!"),

        // ⭐ CHISTES AGREGADOS POR PAUL (el creador de Cumulus)
        j(Kind.CHILE, "¿Qué le dice un hijo palta a su papá palta?\n—Papá, ojalá que no te muelas nunca."),
        j(Kind.CHILE, "¿Cuál es el santo de los completos y las sopaipillas?\n¡San Guchito!"),
        j(Kind.CHILE, "—Amigo, ando buscando un trabajo donde gane harta plata, no haga nada y me respeten.\n—Ah, compadre… usted lo que quiere es ser parlamentario."),
        j(Kind.CHILE, "La profesora le pregunta a Jaimito:\n—A ver, si yo digo «fui rica», es pasado… pero si digo «soy hermosa», ¿qué es?\n—¡Exceso de personalidad, tía!"),
        j(Kind.CHILE, "Dos amigas conversando:\n—Oye, ¿y cómo te va con tu nuevo pololo?\n—Mal, amiga… es como el sol.\n—¿Es tierno y te ilumina?\n—No: me da una insolación cada vez que lo veo… ¡y si lo miro mucho, me quedo ciega de la rabia!"),
        j(Kind.CHILE, "Ella:\n—Amor, ¿me bajas la luna?\nÉl:\n—¿Y creí que soy grúa?"),
        j(Kind.CHILE, "Iba un niño por la calle y en la vereda del frente pasa un caballero. De repente, el niño grita:\n—¡Cayeroon! ¡Cayeroon!\nEl caballero se da vuelta, convencido de que le están hablando a él…\ny el niño, agachado en la vereda, recogiendo unas bolitas que se le cayeron, va contando:\n—Cayeron una… cayeron dos… cayeron tres… ¡cayeron cuatro!", note = "«Cayeron» suena casi igual que «caballero»: el señor creyó que lo llamaban, pero el niño solo contaba lo que se le cayó."),
        // 🇨🇱 Agregados por el usuario (tal como se cuentan en Chile)
        j(Kind.CHILE, "Llega un curadito a la comisaría a las cinco de la mañana arrastrando a un pingüino vivo tomado de la ala.\nEl carabinero de guardia en la entrada se queda mirando la escena sin poder creerlo y le dice:\n—¡Oiga, compadre! ¿Qué hace con ese animal ahí? ¡Llévelo de inmediato al Zoológico!\n—Ya, mi cabo, altiro —dice el curado, y se va con el pingüino.\nAl día siguiente, a la misma hora, vuelve a pasar el curadito por afuera de la comisaría con el mismo pingüino, pero ahora el animal traía lentes de sol y un helado en la pata.\nEl carabinero sale indignado y le grita:\n—¡Oiga, desubicado! ¡Le dije ayer que llevara a ese pingüino al Zoológico!\nY el curado lo mira feliz y le responde:\n—¡Sí po, mi cabo! ¡Ayer lo llevé al Zoológico y lo pasó súper bien! ¡Hoy día lo llevo a Fantasilandian y en la tarde al cine!"),
        j(Kind.CHILE, "Lo paran a un borrachito caminando por la vereda de madrugada. El carabinero, para ver qué tan mal anda, le dice:\n—A ver, caballero... camine derecho por el borde de la solera sin caerse a la calle.\nEl curado da dos pasos, se tambalea entero, casi se cae a la acequia y se frena. Mira al carabinero con cara de indignación y le dice:\n—¡Ah, no po, mi cabo! ¡Usted me pide imposibles! Si con la borrachera que llevo apenas puedo mantener el planeta quieto debajo de las patas, ¡¿pretende que más encima me equilibre en una huincha de diez centímetros?!"),
        j(Kind.CHILE, "Un carabinero para a un compadre que venía haciendo zig-zag en la camioneta a las tres de la mañana.\n—Buenas noches, conductor. Bájese del vehículo, por favor. Le voy a hacer el alcotest.\nEl curadito se baja tambaleándose, mira el aparato y le dice al paco:\n—Oiga, mi cabo... ¿y esa maquinita qué mide?\n—Mide el nivel de alcohol en la sangre, caballero.\nEl curado abre los ojos desorbitados, se agarra el pecho y le dice:\n—¡Por la chucha, mi cabo! ¡Sople usted primero a ver si marca algo, no vaya a ser cosa que la máquina se descalibre y me eche la culpa a mí!"),
        j(Kind.CHILE, "Va un curado caminando de noche por la orilla del río Mapocho. Se asoma sobre el puente, mira para abajo y ve la luna reflejada en el agua.\nEn eso pasa un carabinero caminando y el curado lo agarra del brazo muy impresionado:\n—¡Oiga, mi cabo! Dígame la verdad... ¿eso que está allá abajo es la luna?\nEl carabinero mira para abajo y le dice:\n—Sí, efectivamente, es la luna.\nY el curado lo mira espantado y le pregunta:\n—¡Y entonces qué chucha hago yo tan arriba!"),
        j(Kind.CHILE, "Lo para un carabinero a un compadre que venía manejando a dos kilómetros por hora y haciendo zig-zag en la avenida.\n—Buenas noches, conductor. Por favor bájese del auto y camine por la línea blanca de la calzada para ver cómo anda.\nEl curado lo mira fijamente con los ojos rojos, traga saliva y le dice:\n—Mire, mi cabo... si yo sobrio no bailo ni el Costillar, ¿usted cree que curado le voy a hacer una rutina de gimnasia olímpica sobre la línea?"),
        j(Kind.CHILE, "Se sube un curadito a una micro a las tres de la tarde. Se va tambaleando por el pasillo, mira a la gente con cara de enojado y empieza a gritar a todo pulmón:\n—¡Los de la derecha son unos flojos! ¡Los de la izquierda son unos sinvergüenzas! ¡Y los del fondo son todos unos tontos!\nEl chofer, picado, clava los frenos de golpe. El curado sale volando hacia adelante y se da un golpazo contra la cabina.\nEl chofer lo agarra de la chaqueta y le dice:\n—¡A ver, compadre! ¿Quiénes son los flojos, los sinvergüenzas y los tontos?\nY el curado, sobándose la cabeza, le responde:\n—¡Puta, no sé po, maestro... si con la frenada que te mandaste se me desordenaron todos!"),
        j(Kind.CHILE, "Llega una flota entera de naves espaciales a la Tierra y se posan sobre Chile. Un alienígena baja en un megáfono intergaláctico y le habla a la multitud reunida en la calle:\n—¡Terrestres! Traemos la cura para todas las enfermedades, la energía infinita y la paz universal. Pero a cambio, exigimos que nos entreguen su secreto tecnológico más preciado.\nLa gente se mira entre sí intrigada. Un compadre da un paso al frente, se arregla la gorra y les pregunta:\n—Oiga, mi delgado amigo verde... ¿y cuál sería ese secreto tan valioso?\nEl marciano se acerca y le susurra:\n—Queremos saber... ¿de dónde sacan tanta astucia para hacer chistes de todo lo que pasa en el país en menos de cinco minutos?"),
        j(Kind.CHILE, "El párroco del pueblo llega muy acongojado donde el monaguillo después de la misa:\n—Oye, Juanito... estoy desesperado. Alguien me robó la bicicleta en la entrada del templo. Tengo una idea: este domingo en el sermón voy a nombrar los Diez Mandamientos uno por uno. Cuando llegue a \"No robarás\", voy a fijarme bien en la cara de todos los feligreses. ¡El que ponga cara de culpa, ese fue!\nEl domingo por la tarde, el monaguillo ve al cura paseando feliz en su bicicleta y le pregunta asombrado:\n—¡Padre! ¿Le resultó la estrategia? ¿Encontró al culpable en \"No robarás\"?\nY el cura le responde:\n—Fíjate que no, Juanito... Cuando iba en el sexto mandamiento, \"No cometerás actos impuros\", ¡ahí me acordé  adónde había dejado la bicicleta!"),
        j(Kind.CHILE, "Iba un huaso de noche cruzando los cerros de Olmué cuando de repente baja una luz plateada del cielo y lo abducen. Lo suben a la nave, lo acostaron en una mesa metálica y se le acercan tres marcianos llenos de tubos y cables.\nEl marciano jefe le dice:\n—Humano, te vamos a hacer pruebas científicas avanzadas durante los próximos seis meses para analizar la resistencia de la especie terrestre.\nEl huaso se sienta en la mesa, saca la chupalla, se arregla la manta y les dice:\n—Miren, patroncitos... por mí no hay problema. Pero háganme un favorcito: avísenle a mi señora que me raptaron, ¡porque si llego tarde a la casa sin los kilogramos de harina, a mí me van a hacer pruebas peores!"),
        j(Kind.CHILE, "Un ovni aterriza justo en medio de la Plaza de la Ciudadanía, frente a La Moneda. Sale el marciano más grande, rodeado de luces y humo, y la gente se amontona alrededor asombrada.\nEl marciano levanta un brazo y dice con voz potente:\n—¡Humanos! Exigimos hablar de inmediato con su líder máximo.\nEn eso, se le acerca un compadre de la calle con un vaso de pisco sour en la mano, lo mira fijo y le susurra al oído:\n—Oye, compare... si querí hablar con el que manda de verdad, no entrí a La Moneda; andate al Registro Civil o a la fila del banco a las ocho de la mañana... ¡a ver si a ti te atienden rápido!"),
        j(Kind.CHILE, "Entra un marciano a un bar bien popular en Valparaíso. Todos se quedan callados mirándolo. El marciano camina hasta la barra, se sienta y le dice al barman:\n—Deme su brebaje más fuerte, terrestre.\nEl barman le sirve un terremoto bien cargado con pipa y helado de piña. El marciano se lo toma de un solo sorbo, le brillan los ojos, le salen chispas por las antenas y dice impresionado:\n—¡Increíble! Este combustible nos daría energía para viajar cinco galaxias. ¿Cómo se llama?\nY el barman, limpiando el vaso con un paño, le dice:\n—Se llama Terremoto, compadre... ¡y te apuesto que al segundo vaso no volái ni a la esquina!"),
        j(Kind.CHILE, "Un cura va rindinedo el examen práctico de conducir acompañado por el instructor. Van en pleno centro y el cura se pasa una luz roja.\nEl instructor, sorprendido, le dice:\n—¡Padre! Se acaba de pasar un semáforo en rojo.\nY el cura, muy tranquilo, le responde:\n—No se preocupe, hijo mío, San Pedro me acompaña y me protege.\nA las dos cuadras, el cura no hace el disco pare y casi choca con una camioneta. El instructor, agarrado del tablero y sudando frío, le grita:\n—¡Padre, por favor! ¡Casi nos matamos!\nY el cura le sonríe:\n—Tranquilo, mi hijo, que San Pedro va con nosotros.\nSiguen tres cuadras más y el cura dobla a la izquierda en una calle con flecha contra el tránsito. En eso el instructor frena de golpe el auto con el doble pedal, se baja corriendo, abre la puerta del copiloto y dice:\n—¡Mire, Padre, hasta aquí llego yo! ¡Porque estoy seguro de que San Pedro se bajó en la primera esquina!"),
        j(Kind.CHILE, "Un huaso va manejando su camioneta por el camino rural y lleva al loro afirmado en el manubrio. De repente lo para un carabinero en un control de tránsito:\n—Buenas tardes, conductor. Documentos del vehículo, licencia y revisión técnica, por favor.\nEl huaso se mete la mano al bolsillo buscando los papeles, y el loro le dice al paco:\n—¡No busque nada, mi cabo! ¡Si no tiene licencia, anda con la revisión vencida desde el año pasado y más encima se tomó dos vinos en el casino!\nEl huaso, indignado, agarra al loro del pescuezo, lo mete en una bolsa y lo tira al asiento de atrás.\nEl carabinero le dice:\n—Ya, bájese del vehículo, está detenido.\nY desde la bolsa en el asiento de atrás se escucha la voz del loro:\n—¡Por huevón! ¡Te dije que no me llevaras sin comer!"),
        j(Kind.CHILE, "Va un compadre cruzando la frontera de vuelta a Chile y lleva un loro escondido debajo de la chaqueta para que no se lo quiten en el control SAG.\nEl fiscalizador lo para, lo mira sospechoso y le pregunta:\n—A ver, caballero... ¿lleva alguna fruta, vegetal o animal no declarado?\n—No, mi inspector, nada de nada. Puras cosas personales —responde el tipo tratando de poner cara de póker.\nEn eso, el loro le saca la cabeza por el cuello de la chaqueta, mira al carabinero y le grita:\n—¡Mentira, mi cabo! ¡Trae tres cartones de cigarros en el maletero y dos botellas de pisco debajo del asiento! ¡Y a mí me lleva apretado como zapato chico!"),
        j(Kind.CHILE, "Un compadre llega cansado del trabajo y encuentra al loro gritándole malas palabras, insultando a los vecinos y haciendo un desastre en la cocina. El tipo, picado y perdiendo la paciencia, agarra al loro de las alas y lo mete directo al congelador de la refrigeradora para que se le quiten las mañas.\nPasan unos diez minutos y el hombre empieza a sentir culpa: \"Chócale, se me pasó la mano, se me va a congelar el animal\".\nCorre a abrir la puerta del refrigerador y ve al loro temblando de frío, con los picos helados, abrazándose las plumas.\nEl loro lo mira muy humilde y le dice:\n—S-s-señor... le p-pido mil disculpas por m-mi comportamiento... Prometo que de hoy en adelante voy a ser el loro más educado y respetuoso del mundo.\nEl dueño, asombrado por el cambio tan rápido, le responde:\n—Bueno, me alegro de que hayas entendido la lección.\nY el loro, tiritando de frío, apunta hacia el fondo del congelador y le susurra:\n—Una consulta no más, jefe... ¿y qué le hizo el pollo ese que está ahí desplumado y congelado?"),
        j(Kind.CHILE, "Un ladrón entra a robar a una casa a oscuras en medio de la noche. Va caminando despacito con la linterna cuando de repente escucha una voz tenebrosa desde la penumbra:\n—¡Jesús te está mirando!\nEl ladrón se hiela del susto, apaga la linterna y se queda inmóvil. Espera unos minutos, no se oye nada, prende la linterna de nuevo y sigue guardando las cosas de valor. En eso, se escucha otra vez la misma voz:\n—¡Jesús te está mirando!\nAsustado, el tipo mueve el foco de la linterna hacia el rincón del techo y ve a un loro adentro de una jaula. El ladrón suspira aliviado y se ríe:\n—¡Ah, era un pobre loro! ¿Y cómo te llamái, pajarraco?\n—Me llamo Aníbal —responde el loro.\n—¡Ja! ¿Y qué dueño tan ridículo le pone Aníbal a un loro?\nY el loro, mirándolo fijamente, le responde:\n—El mismo ridículo que le puso \"Jesús\" al Dóberman que está detrás tuyo."),
        j(Kind.CHILE, "Un loro va viajando en primera clase en un avión muy elegante. Pasa la azafata y el loro, muy alzado, le grita:\n—¡Oye, flaca! ¡Traeme un pisco sour altiro!\nLa azafata, sorprendida pero educada, le lleva el trago. A los dos minutos, el loro vuelve a gritar:\n—¡Oye! ¿Y el maní, poh? ¡Mueve las nalgas!\nUn caballero muy serio que iba al lado, al ver la frescura del loro, decide imitarlo para que a él también lo atiendan rápido y le grita a la azafata:\n—¡A ver, señorita! ¡Tráigame un whisky rápido y sin chistar!\nLa azafata, ya cansada del maltrato, va donde el capitán. El piloto sale indignado, toma al loro y al pasajero del cuello, abre la puerta de emergencia y ¡los tira a los dos fuera del avión!\nMientras van cayendo en picada a miles de metros de altura, el loro mira al tipo, despliega las alas y le pregunta muy tranquilo:\n—Oye, compadre... y para ser tan choro con la azafata, ¿sabís volar por lo menos?"),
        j(Kind.CHILE, "Un huaso llega a la feria de Curacaví con una jaula y un loro adentro gritando como loco:\n—¡A quinientos pesos! ¡A quinientos pesos el loro!\nSe le acerca un comprador interesado, le pasa las quinientas lucas... perdón, los quinientos pesos, y le pregunta:\n—Oiga, y este loro, ¿habla harto?\nY el huaso lo mira muy serio y le responde:\n—¡Puta, jefe, no sé si hablará mucho!... ¡Pero harto sapo es el culiao, si me acaba de vender en quinientos pesos!"),
        j(Kind.CHILE, "Un huaso fanfarrón le está presumiendo su perro a un compadre que vino de la ciudad:\n—Mire, don José, este perro que tengo aquí es lo más inteligente que dio la tierra. Le digo \"tráeme la manta\" y va a la casa y me la trae. Le digo \"busca la chupalla\" y me la pone en la mano.\nEl compadre de la ciudad, incrédulo, saca un billete de veinte lucas, se lo pone al perro en la boca y le dice:\n—A ver, perrito... anda al almacén del pueblo y cómprame una cajetilla de cigarros.\nEl perro sale disparado como un rayo.\nPasan dos horas, tres horas, y el perro no vuelve. El compadre ya molesto le dice al huaso:\n—¡Ya ves tú! ¡Tu perro se arrancó con mis veinte lucas!\nSalen a buscarlo al camino y lo encuentran al lado de una perrita quiltra en medio del matorral. El huaso lo mira asombrado y le dice al compadre:\n—¡Ay, patroncito, perdone al pobre animal!... ¡Es que nunca le habían pasado tanta plata junta!"),
        j(Kind.CHILE, "Llega un huaso a una feria libre en el pueblo para comprar un gallo fino para su gallinero. Encuentra un puestero que tiene uno encaramado en una jaula:\n—Oiga, compadre, ¿y este gallo que tiene acá es bueno para las gallinas?\nEl puestero le responde henchido de orgullo:\n—¡Uuuuh, patroncito! Este gallo es una maravilla: pisa a las gallinas, pisa a los patos, a los pavos y si se descuida, ¡hasta a los perros!\nEl huaso, impresionado, se lo compra y se lo lleva al fundo.\nAl día siguiente, a primera hora, sale el huaso a mirar el patio y ve al gallo tirado en el suelo, tieso, con las patas para arriba y dos buitres volando en círculos arriba de él.\nEl huaso se le acerca triste, lo mira y le dice:\n—¡Puta el gallo flojo, po! ¡Le quedó grande el campo!\nY el gallo abre un ojo despacito, lo mira de lado y le susurra:\n—¡Cállese, huaso desubicado, que me va a espantar a las palomas!"),
        j(Kind.CHILE, "El pololo llega a su casa, se abrazan y se besan. Su padre mira por la ventana y le dice:\n—Mira, po, vieja, mira cómo tiene agarrada a cabra ahí. ¡Mira, po, dónde está metiendo las mano el cabro he mierda!\n—Yaaa, ¿paque le poní tanto color, viejo? ¿Acaso no te acordai cuando me dabai los porrazos en los matorrales?\n—Sí, po, vieja, pero eran otros tiempos.\n—¿Por qué no lo invitas para controlarlo acá mejor?\nLo hacen pasar, lo invitan a tomar once y le ofrecen empanadas. A los minutos, la señora le pregunta:\n—¿Cómo estuvieron las empanadas, hijo?\n—Bien, tía, estuvieron súper rica las empanadas de marisco.\nLa señora lo mira y le dice:\n—Eran de pollo… ¡Anda a lavarte las mano!"),
        j(Kind.GENERAL, "¿Qué hace Shakira cuando le llega un mensaje al celular?\n¡Lo lee, lo lee, lo lee!"),
        j(Kind.GENERAL, "¿Por qué los buzos se tiran al mar hacia atrás para bajarse del bote?\nPorque si se tiraran hacia adelante… ¡caerían adentro del bote!"),
        j(Kind.QUE_LE_DIJO, "¿Qué le dice una iguana a su hermana gemela?\n—¡Somos iguanitas!"),
        j(Kind.GENERAL, "¿Por qué los elefantes no juegan en la computadora?\n¡Porque le tienen miedo al mouse!"),
        j(Kind.GENERAL, "¿Qué hace una abeja en el gimnasio?\n¡Zumba!"),
        // 💑 De parejas (para grandes): solo salen si pides «chistes de parejas, de casados, de matrimonio, para adultos o pícaros»
        j(Kind.PAREJAS, "El marido llega de la calle y le dice a su esposa:\n—Mi amor, ¡me gané el Loto! Arma las maletas altiro.\n—¡Qué buena, mi vida! ¿Pongo ropa de frío o de calor?\n—Me da lo mismo… pero te me vas de la casa ahora ya."),
        j(Kind.PAREJAS, "Una señora va al Registro Civil a pedir el divorcio:\n—Señorita, vengo a divorciarme porque mi marido es un cochino, un flojo y me trata pésimo.\n—¿Y cuánto tiempo llevan casados?\n—Nos casamos mañana… pero es para ir adelantando el trámite."),
        j(Kind.PAREJAS, "—Vieja… tengo que confesarte algo antes de partir. Te fui infiel con tu mejor amiga, con la vecina del frente y con tu hermana…\n—Ya, viejo, no hables más. Quédate tranquilo… y deja que el veneno haga su efecto."),
        j(Kind.PAREJAS, "—Mi amor, si yo me muero… ¿tú llorarías por mí?\n—Obvio, mi vida… si tú sabes que yo lloro por cualquier güeá."),
        j(Kind.PAREJAS, "Un gallo llega curado a la casa y le dice a su esposa:\n—Amor, estoy sin pega… no tengo plata pa' pagar la luz, el agua ni el cable.\nY sale el vecino del clóset y le dice:\n—¡Buta! Yo te puedo pagar la luz… ¡pero paga algo vo' también po!"),
        j(Kind.PAREJAS, "—Mi amor, ¿tú encuentras que estoy gorda?\n—Para nada, mi cielo… el clóset es el que se achicó."),
        // 😄 Agregados por el usuario (tal como se cuentan en Chile)
        j(Kind.PAREJAS, "Un don Lucho va al médico porque sospecha que su señora se está quedando sorda.\nEl doctor le dice:\n—Para probar, póngase a diez metros y hágale una pregunta. Si no responde, acérquese a cinco metros, y si tampoco, a un metro.\nLlega a la casa, la ve cocinando de espaldas y a diez metros le grita:\n—¡María! ¿Qué hay de almuerzo?\nSin respuesta. Se acerca a cinco metros:\n—¡María! ¿Qué hay de almuerzo?\nSigue sin responder. Se le pone al lado, al oído:\n—¡María! ¿Qué hay de almuerzo?\nLa señora se da vuelta cansada y le dice:\n—¡Lucho, por tercera vez: CAZULA DE AVE!"),
        j(Kind.PAREJAS, "Un matrimonio compra un robot con detector de mentiras que le pega una cachetada a cualquiera que diga una mentira.\nEl marido le pregunta al hijo:\n—A ver, mijito, ¿dónde estuviste esta tarde?\n—En la biblioteca estudiando, papá.\n¡ZAP! El robot le mete un chapazo al cabro.\n—Ya, está bien, estaba viendo películas con los amigos.\n—¡Viste! —dice el papá—. A tu edad yo jamás le mentía a mis papás.\n¡ZAP! El robot le manda un chapazo al papá.\nLa señora se mata de la risa y dice:\n—¡Ja! Tenía que ser tu hijo no más...\n¡ZAP! Le llega el chapazo a la señora."),
        j(Kind.PAREJAS, "Un compadre sospecha de la señora y decide comprar un loro bien vivo para que cuide la casa y le cuente todo.\nAl día siguiente, cuando el marido llega del trabajo, el loro empieza a gritar desde la jaula:\n—¡Oye! ¡Vino el vecino! ¡Vino el vecino!\nEl marido, sulfurado, le dice:\n—¡Aha! ¿Y qué hicieron?\nY el loro responde:\n—¡Puta, no sé po, si me taparon la jaula con una frazada!"),
        j(Kind.PAREJAS, "Llega el pololo a la casa de la polola por primera vez a almorzar con los suegros. En medio del almuerzo, el suegro lo mira fijamente y le pregunta:\n—Y dígame, joven... ¿usted qué intenciones tiene con mi hija?\nEl cabro, súper nervioso, traga saliva y le responde:\n—Mire, suegro, para serle sincero... ¡yo no más venía por el pastel de choclo, pero si me da permiso para ser el pololo, bienvenido sea!"),

        // 😄 GENERALES
        j(Kind.GENERAL, "—Mamá, en el colegio me dicen despistado.\n—Niño, ¡esta no es tu casa!"),
        j(Kind.GENERAL, "¿Qué hace una vaca pensando?\n¡Leche concentrada!"),
        j(Kind.GENERAL, "¿Por qué el libro de matemáticas está triste?\n¡Porque tiene muchos problemas!"),
        j(Kind.GENERAL, "¿Cuál es el animal más antiguo?\nLa cebra… ¡porque está en blanco y negro!"),
        j(Kind.GENERAL, "—¿Cuál es tu plato favorito?\n—El hondo… ¡porque cabe más!"),
        j(Kind.GENERAL, "—Doctor, doctor, me duele aquí, aquí y aquí.\n—Usted tiene el dedo roto."),
        j(Kind.GENERAL, "¿Por qué los pájaros no usan Facebook?\n¡Porque ya tienen Twitter!"),
        j(Kind.GENERAL, "—Me compré un diccionario.\n—¿Y qué tal?\n—¡Me quedé sin palabras!"),
        j(Kind.GENERAL, "Van dos y se cae el del medio."),
        j(Kind.GENERAL, "¿Por qué la escoba está feliz?\n¡Porque ba-rrió todos sus problemas!"),
        j(Kind.GENERAL, "¿Qué le dijo el lápiz al papel?\n—¡Contigo escribo mi historia!"),
        j(Kind.GENERAL, "—Camarero, hay una mosca en mi sopa.\n—No se preocupe, señor, no se va a tomar mucho."),
        j(Kind.GENERAL, "¿Qué le dijo el perro al hueso?\n—Hoy no puedo salir, ¡tengo que roerte!"),
        j(Kind.GENERAL, "¿Por qué el tomate no toma café?\n¡Porque toma-te!"),
        j(Kind.GENERAL, "¿Qué hace un perro con un taladro?\n¡Taladrando!")
    )

    // 😁 ANTES: se ríe al presentarlo · 🤭 DURANTE: se le escapa la risa antes del remate · 🤣 DESPUÉS: se ríe al terminar
    private val INTROS = listOf("¡Jajaja, atento, que este es bueno! 😁", "¡Jejeje, ahí va uno recién salido del horno! 😄",
        "¡Jajaja, prepárate, que este me encanta! 😆", "¡Jijiji, uy, este es de mis favoritos! 🤭",
        "¡Jaja, a ver si te saco una sonrisa! 😁", "¡Jajaja, siéntate, que este es bueno! 😄")
    private val DURING = listOf("(jejeje… 🤭)", "(jijiji… 😆)", "(ay, ya me estoy riendo… 😂)", "(jajaja, espera, espera… 🤭)",
        "(jeje, aquí viene lo mejor… 😁)")
    private val LAUGHS = listOf("¡Jajajaja! 🤣", "¡Jajaja, no me aguanto! 😂", "¡Jajaja! 😆", "¡Jajajaja, qué bueno! 🤣", "¡Ja, ja, ja! 😂")
    private val CLOSES = listOf("¿Te cuento otro? 😄", "¿Quieres otro? Pídeme uno chileno, mexicano, japonés… ¡o una paya! 😁",
        "¿Otro más? 😆", "¡Pídeme otro cuando quieras! 😄")

    // ------------------------------------------------------------------------
    // 🧭 QUÉ PIDE
    // ------------------------------------------------------------------------
    private val askRx = Regex("(?iu)chistes?|payas?|adivinanzas?|adivina|trabalenguas|toc[ -]?toc|knock|el colmo|colmos|qu[eé] le (dijo|dice)|piadas?|dajare|ajae")
    private val anotherRx = Regex("(?iu)^(?:(?:y|ya|ok|dale|bueno|jaja\\S*|jeje\\S*)[,!\\s]+)*(?:(?:cu[eé]ntame|dime|dame|tira(?:me)?|hazme|echa(?:te)?)\\s+)?" +
        "(?:otro|otra|uno m[aá]s|una m[aá]s|otro m[aá]s|otra m[aá]s|m[aá]s|de nuevo|otra vez)" +
        "(?:[,\\s]+(?:m[aá]s|chiste|paya|adivinanza|por favor|po|porfa|pero|diferente|distinto|nuevo|jaja\\S*|jeje\\S*|jiji\\S*|xd))*[!.?\\s]*$")

    /** ¿Pide chistes de la colección? Devuelve los tipos (vacío = no pide chistes). */
    fun requestedKinds(q: String): List<Kind> {
        val s = q.lowercase()
        if (!askRx.containsMatchIn(s)) return emptyList()
        return kindsIn(s)
    }

    /** Tipos nombrados en el texto (sin exigir la palabra «chiste»). Si no nombra ninguno: chistes generales. */
    private fun kindsIn(s: String): List<Kind> {
        val out = mutableListOf<Kind>()
        if (Regex("chilen|(?<![\\p{L}])chile(?![\\p{L}])").containsMatchIn(s) && !s.contains("paya")) out += Kind.CHILE
        if (Regex("argentin|porteñ").containsMatchIn(s)) out += Kind.ARGENTINA
        if (Regex("mexican|de m[eé]xico|pepito").containsMatchIn(s)) out += Kind.MEXICO
        if (Regex("gringo|norteamerican|estadounidense|americano|estados unidos|en ingl[eé]s|dad joke|(de|del|en) (los )?(usa|eeuu|ee\\.? ?uu\\.?)(?![\\p{L}])|yanqui").containsMatchIn(s)) out += Kind.USA
        if (Regex("toc[ -]?toc|knock").containsMatchIn(s)) out += Kind.TOCTOC
        if (Regex("chin[oa]s?(?![\\p{L}])|de china").containsMatchIn(s)) out += Kind.CHINA
        if (Regex("japon[eé]s|japonesa|de jap[oó]n|dajare").containsMatchIn(s)) out += Kind.JAPON
        if (Regex("corean|de corea|ajae").containsMatchIn(s)) out += Kind.COREA
        if (Regex("portugu[eé]s|portuguesa|portugal|portugueñ").containsMatchIn(s)) { out += Kind.PORTUGAL; if (s.contains("portugueñ")) out += Kind.BRASIL }
        if (Regex("brasile|brasil|piada").containsMatchIn(s)) out += Kind.BRASIL
        if (s.contains("paya")) out += Kind.PAYA
        if (Regex("adivinanza|adivina").containsMatchIn(s)) out += Kind.ADIVINANZA
        if (s.contains("trabalengua")) out += Kind.TRABALENGUAS
        if (Regex("colmo").containsMatchIn(s)) out += Kind.COLMO
        if (Regex("qu[eé] le (dijo|dice)").containsMatchIn(s)) out += Kind.QUE_LE_DIJO
        if (Regex("parejas?|matrimonio|casados|marido|esposa|suegra|p[ií]car|adultos|para grandes|de viejos|pololos").containsMatchIn(s)) out += Kind.PAREJAS
        if (out.isEmpty()) out += DEFAULT_KINDS
        return out.distinct()
    }

    /** «otro chileno», «uno de parejas», «otra paya», «uno mexicano pero diferente jaja», «uno de Pepito» (después de un chiste). */
    private val anotherOfRx = Regex("(?iu)^(?:(?:y|ya|ok|dale|bueno|jaja\\S*|jeje\\S*|oye|ahora)[,!\\s]+)*(?:(?:cu[eé]ntame|dime|dame|tira(?:me)?|hazme|echa(?:te)?)\\s+)?" +
        "(?:otro|otra|otros|otras|uno|una|unos|unas)(?:\\s+m[aá]s)?\\s+(.{2,60})$")

    /**
     * Después de un chiste: «otro chileno», «uno de parejas»… → los tipos que nombra. Null si no es eso.
     * («otro pero diferente», «otro jaja» → null aquí; eso lo resuelve [asksAnother]).
     */
    fun anotherOfKinds(q: String): List<Kind>? {
        val m = anotherOfRx.find(q.trim()) ?: return null
        val rest = m.groupValues[1].lowercase()
            .replace(Regex("(?iu)\\b(pero|m[aá]s|diferente|distinto|nuevo|por favor|porfa|po|jaja\\S*|jeje\\S*|jiji\\S*|xd|chiste|chistes)\\b"), " ")
            .replace(Regex("[!?.,¡¿]"), " ").trim()
        if (rest.isBlank()) return null
        val kinds = kindsIn(rest)
        // Si no nombró un país o tipo que conozcamos («otro de dinosaurios»), no es de la colección
        return if (kinds == DEFAULT_KINDS) null else kinds
    }

    private val DEFAULT_KINDS = listOf(Kind.GENERAL, Kind.QUE_LE_DIJO, Kind.COLMO, Kind.CHILE)

    /**
     * 🌐 «Quiero un chiste de internet», «busca un chiste de internet», «dime una paya de internet»,
     * «quiero que me cuentes un chiste argentino de internet»: se busca en internet (no en la colección).
     */
    private val internetRx = Regex("(?iu)(?:de|en|por|desde|con)\\s+(?:el\\s+)?internet|de la web|de google|en google|" +
        "b[uú]sca(?:me|nos|r)?\\s+(?:en internet\\s+)?(?:un|una|unos|unas|alg[uú]n|alguna|otro|otra)?\\s*(?:chistes?|payas?|adivinanzas?|trabalenguas|piadas?)")

    fun wantsInternet(q: String): Boolean = internetRx.containsMatchIn(q) && askRx.containsMatchIn(q)

    /** ¿Es «otro», «uno más», «cuéntame otro»…? */
    fun asksAnother(q: String): Boolean = anotherRx.matches(q.trim())

    /** «cuéntame 3 chistes» → 3 (máximo 5); «chistes» en plural → 2; si no, 1. */
    fun howMany(q: String): Int {
        val s = q.lowercase()
        Regex("(?<![\\d])([2-9])(?![\\d])").find(s)?.let { return it.groupValues[1].toInt().coerceAtMost(5) }
        val words = mapOf("dos" to 2, "tres" to 3, "cuatro" to 4, "cinco" to 5, "un par" to 2, "varios" to 3, "varias" to 3)
        words.entries.firstOrNull { Regex("(?<![\\p{L}])${it.key}(?![\\p{L}])").containsMatchIn(s) }?.let { return it.value }
        return if (Regex("(?<![\\p{L}])(chistes|payas|adivinanzas|trabalenguas|colmos)(?![\\p{L}])").containsMatchIn(s)) 2 else 1
    }

    /** Tema pedido («chiste de perros» → «perr»); null si no pidió un tema. */
    fun topic(q: String): String? {
        val m = Regex("(?iu)(?:chistes?|payas?|adivinanzas?)\\s+(?:de|sobre|del|con)\\s+(?:la |el |los |las |un |una )?(\\p{L}{3,})").find(q) ?: return null
        val t = m.groupValues[1].lowercase()
        // Los países y tipos no son temas
        if (Regex("^(chile|argentin|m[eé]xic|china|chin|jap[oó]n|corea|portugal|brasil|estados|toc|colmo|pepito|humor|risa|amor|todo|cualquier|ni[ñn]os|buenos?|cortos?|parej|matrimon|casad|marid|espos|suegr|adult|grandes|viejos|pololo|p[ií]car)").containsMatchIn(t)) return null
        return t.dropLast(if (t.length > 5) 2 else 0)   // «perros» → «perr», «doctores» → «doctor»
    }

    /**
     * Elige [count] chistes de [kinds] que no estén en [used] (los que ya contó). Si se acabaron los de ese tipo,
     * devuelve null cuando [canSearch] (para buscar más en internet) o vuelve a empezar la ronda si no hay internet.
     */
    @Suppress("UNUSED_PARAMETER")
    fun pick(kinds: List<Kind>, count: Int, used: Set<String>, topic: String?, canSearch: Boolean, random: java.util.Random = java.util.Random(),
             usedOrder: List<String> = emptyList()): List<Joke>? {
        var pool = ALL.filter { it.kind in kinds }
        if (topic != null) {
            pool = ALL.filter { (it.text + " " + it.answer).lowercase().contains(topic) && (it.kind != Kind.PAREJAS || Kind.PAREJAS in kinds) }
            if (pool.isEmpty()) return null   // tema que no está en la colección → lo cuenta la IA
        }
        var fresh = pool.filter { it.id !in used }
        if (fresh.isEmpty()) {
            // 🔁 Ya contó todos los de ese tipo: empieza otra ronda (con o sin internet), primero los que contó hace más
            //    tiempo y nunca los últimos que acabas de ver. Antes, con internet, respondía la IA y copiaba el último chiste.
            val order = usedOrder.ifEmpty { used.toList() }
            val recent = order.takeLast(minOf(pool.size - count, maxOf(1, pool.size / 2)).coerceAtLeast(0)).toSet()
            fresh = pool.filter { it.id !in recent }.sortedBy { order.indexOf(it.id) }.take(maxOf(count, 2))   // de los 2 más antiguos: todos vuelven a salir
            if (fresh.isEmpty()) fresh = pool
        }
        // Se reparte entre los tipos pedidos (ej. «chistes chilenos y argentinos»)
        val out = mutableListOf<Joke>()
        val byKind = fresh.groupBy { it.kind }.mapValues { it.value.shuffled(random).toMutableList() }.toMutableMap()
        while (out.size < count && byKind.values.any { it.isNotEmpty() }) {
            for (k in byKind.keys.shuffled(random)) {
                val l = byKind[k] ?: continue
                if (l.isNotEmpty() && out.size < count) out += l.removeAt(0)
            }
        }
        return out.ifEmpty { null }
    }

    /** El texto que ve el usuario: presentación con sonrisa, el chiste, la risa y la invitación a otro. */
    /** 🤭 Mete una risita justo antes del remate (la última línea), o a la mitad si el chiste es de una sola línea. */
    private fun withGiggle(text: String, random: java.util.Random): String {
        val giggle = DURING[random.nextInt(DURING.size)]
        val lines = text.lines()
        if (lines.size >= 2) return (lines.dropLast(1) + giggle + lines.last()).joinToString("\n")
        // Una sola línea: después de «…», «:» o la primera coma
        val cut = listOf("… ", ": ", ", ").mapNotNull { sep -> text.indexOf(sep).takeIf { it in 8 until text.length - 8 }?.let { it + sep.length } }.minOrNull()
            ?: return "$text $giggle"
        return text.substring(0, cut).trimEnd() + " " + giggle + " " + text.substring(cut)
    }

    /** Palabras de la respuesta para saber si adivinó: «El aguacate (la palta)» → [aguacate, palta]. */
    fun answerKeys(joke: Joke): List<String> = norm(joke.answer).split(Regex("[^a-z]+"))
        .filter { it.length >= 3 && it !in setOf("los", "las", "del", "una", "uno", "que") }

    /** Minúsculas y sin tildes (para comparar lo que dijo por voz). */
    fun norm(s: String): String = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    /**
     * @param hideAnswers por voz: la adivinanza va SIN la respuesta (la dice cuando el usuario responde o se rinde).
     */
    fun format(jokes: List<Joke>, random: java.util.Random = java.util.Random(), hideAnswers: Boolean = false): String {
        val sb = StringBuilder(INTROS[random.nextInt(INTROS.size)])
        for ((i, joke) in jokes.withIndex()) {
            sb.append("\n\n")
            if (jokes.size > 1) sb.append(i + 1).append(". ")
            if (joke.kind != Kind.GENERAL) sb.append(joke.kind.flag).append(" ").append(joke.kind.label).append(":\n")
            // Adivinanzas y trabalenguas no tienen remate: la risita va antes de la respuesta o al final
            sb.append(if (joke.answer.isBlank() && joke.kind != Kind.TRABALENGUAS) withGiggle(joke.text, random) else joke.text)
            if (joke.answer.isNotBlank() && hideAnswers) sb.append("\n\n🤫 ¿Qué es? ¡Dime tu respuesta! ").append(DURING[random.nextInt(DURING.size)])
                .append("\n(Si no la sabes, dime «me rindo» y te la digo 😄)")
            else if (joke.answer.isNotBlank()) sb.append("\n\n🤫 ¿La sabes? Piénsalo un poquito… ").append(DURING[random.nextInt(DURING.size)])
                .append("\n👉 Respuesta: ").append(joke.answer).append("\n¡Jajaja! 😄")
            if (joke.note.isNotBlank()) sb.append("\n💡 ").append(joke.note)
            if (joke.kind != Kind.TRABALENGUAS && joke.answer.isBlank()) sb.append("\n").append(LAUGHS[random.nextInt(LAUGHS.size)])
            else if (joke.kind == Kind.TRABALENGUAS) sb.append("\n¡Jajaja, a ver si lo dices tres veces rápido! 😜")
        }
        if (!(hideAnswers && jokes.any { it.answer.isNotBlank() })) sb.append("\n\n").append(CLOSES[random.nextInt(CLOSES.size)])
        return sb.toString()
    }
}
