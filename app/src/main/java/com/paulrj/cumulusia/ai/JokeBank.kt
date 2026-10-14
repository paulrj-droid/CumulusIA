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
        if (Regex("gringo|norteamerican|estadounidense|americano|estados unidos|en ingl[eé]s|dad joke").containsMatchIn(s)) out += Kind.USA
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

    fun format(jokes: List<Joke>, random: java.util.Random = java.util.Random()): String {
        val sb = StringBuilder(INTROS[random.nextInt(INTROS.size)])
        for ((i, joke) in jokes.withIndex()) {
            sb.append("\n\n")
            if (jokes.size > 1) sb.append(i + 1).append(". ")
            if (joke.kind != Kind.GENERAL) sb.append(joke.kind.flag).append(" ").append(joke.kind.label).append(":\n")
            // Adivinanzas y trabalenguas no tienen remate: la risita va antes de la respuesta o al final
            sb.append(if (joke.answer.isBlank() && joke.kind != Kind.TRABALENGUAS) withGiggle(joke.text, random) else joke.text)
            if (joke.answer.isNotBlank()) sb.append("\n\n🤫 ¿La sabes? Piénsalo un poquito… ").append(DURING[random.nextInt(DURING.size)])
                .append("\n👉 Respuesta: ").append(joke.answer).append("\n¡Jajaja! 😄")
            if (joke.note.isNotBlank()) sb.append("\n💡 ").append(joke.note)
            if (joke.kind != Kind.TRABALENGUAS && joke.answer.isBlank()) sb.append("\n").append(LAUGHS[random.nextInt(LAUGHS.size)])
            else if (joke.kind == Kind.TRABALENGUAS) sb.append("\n¡Jajaja, a ver si lo dices tres veces rápido! 😜")
        }
        sb.append("\n\n").append(CLOSES[random.nextInt(CLOSES.size)])
        return sb.toString()
    }
}
