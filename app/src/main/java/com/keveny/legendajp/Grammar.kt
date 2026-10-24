package com.keveny.legendajp

import com.atilika.kuromoji.ipadic.Token

private typealias P = (Token) -> Boolean

/**
 * Catálogo de pontos gramaticais (N5–N4, alguns N3) detectados por regras sobre os
 * tokens do Kuromoji. Cada regra é uma sequência de condições sobre tokens consecutivos.
 */
object Grammar {

    data class Point(
        val id: String,
        val name: String,        // como aparece para o usuário: 「～ている」
        val level: String,       // N5, N4, N3
        val explanation: String, // curta, em português
        val example: String
    )

    data class Hit(val point: Point, val start: Int, val end: Int)

    private class Rule(val point: Point, val steps: List<P>)

    // ---------- predicados ----------

    private fun base(t: Token) = if (t.baseForm == "*" || t.baseForm.isNullOrEmpty()) t.surface else t.baseForm
    private fun s(vararg v: String): P = { t -> t.surface in v }
    private fun b(vararg v: String): P = { t -> base(t) in v }
    private fun p1(vararg v: String): P = { t -> t.partOfSpeechLevel1 in v }
    private fun p2(vararg v: String): P = { t -> t.partOfSpeechLevel2 in v }
    private fun cf(vararg v: String): P = { t -> t.conjugationForm in v }
    private fun and(vararg ps: P): P = { t -> ps.all { it(t) } }
    private fun or(vararg ps: P): P = { t -> ps.any { it(t) } }
    private val verb: P = p1("動詞")
    private val verbMain: P = and(p1("動詞"), p2("自立"))
    private val adjI: P = p1("形容詞")
    private val noun: P = p1("名詞")
    private val aux: P = p1("助動詞")
    private val renyou: P = and(verb, cf("連用形", "連用タ接続", "連用デ接続"))          // 変わり / 聞い / 読ん
    private val mizen: P = and(verb, cf("未然形", "未然ウ接続", "未然ヌ接続", "未然レル接続")) // 変わら / 食べ
    private val te: P = and(p1("助詞"), s("て", "で"))
    private val ta: P = and(aux, b("た"))
    private val taSurf: P = and(aux, s("た", "だ"))
    private val nai: P = and(aux, b("ない", "ぬ", "ん"))
    private val masu: P = and(aux, b("ます"))
    private val plain: P = and(or(verb, adjI, aux), cf("基本形"))

    // ---------- catálogo ----------

    private fun pt(id: String, name: String, level: String, explanation: String, example: String) =
        Point(id, name, level, explanation, example)

    private val rules: List<Rule> = listOf(
        Rule(pt("masu", "～ます", "N5", "Forma polida do verbo, usada com pessoas que não são íntimas. 変わる → 変わります.", "毎日日本語を勉強します。"),
            listOf(renyou, masu)),
        Rule(pt("mashita", "～ました", "N5", "Passado polido: ação já concluída, em registro educado.", "昨日映画を見ました。"),
            listOf(renyou, and(masu, cf("連用形")), ta)),
        Rule(pt("masen", "～ません", "N5", "Negativo polido: \"não faço / não vou fazer\".", "肉を食べません。"),
            listOf(renyou, and(masu, cf("未然形")), and(aux, s("ん")))),
        Rule(pt("masenka", "～ませんか", "N5", "Convite educado: \"não quer…?\".", "一緒に行きませんか。"),
            listOf(renyou, and(masu, cf("未然形")), and(aux, s("ん")), and(p1("助詞"), s("か")))),
        Rule(pt("mashou", "～ましょう", "N5", "Sugestão ou proposta: \"vamos…\".", "そろそろ帰りましょう。"),
            listOf(renyou, and(masu, s("ましょ")), and(aux, s("う")))),
        Rule(pt("ta", "～た (passado)", "N5", "Passado informal. 食べる → 食べた, 行く → 行った.", "友達に会った。"),
            listOf(and(renyou, cf("連用タ接続", "連用デ接続", "連用形")), taSurf)),
        Rule(pt("nai", "～ない", "N5", "Negativo informal: \"não…\". 行く → 行かない.", "今日は行かない。"),
            listOf(mizen, and(nai, cf("基本形")))),
        Rule(pt("nakatta", "～なかった", "N5", "Passado negativo informal: \"não fiz\".", "宿題をしなかった。"),
            listOf(mizen, and(nai, cf("連用タ接続")), ta)),
        Rule(pt("te-iru", "～ている", "N5", "Ação em andamento (\"estou fazendo\") ou estado resultante (\"estou casado\", \"a porta está aberta\").", "今テレビを見ている。"),
            listOf(renyou, te, and(verb, b("いる", "居る")))),
        Rule(pt("te-kudasai", "～てください", "N5", "Pedido educado: \"por favor, faça…\".", "ここに名前を書いてください。"),
            listOf(renyou, te, b("くださる", "ください"))),
        Rule(pt("te-mo-ii", "～てもいい", "N5", "Permissão: \"pode…\". Com か vira pergunta: \"posso…?\".", "写真を撮ってもいいですか。"),
            listOf(renyou, te, and(p1("助詞"), s("も")), or(b("いい", "よい", "良い"), s("いい")))),
        Rule(pt("te-wa-ikenai", "～てはいけない", "N4", "Proibição: \"não pode…\".", "ここで泳いではいけない。"),
            listOf(renyou, te, and(p1("助詞"), s("は")), b("いける", "いけない"))),
        Rule(pt("te-kara", "～てから", "N5", "Sequência: \"depois de fazer A, B\".", "手を洗ってから食べる。"),
            listOf(renyou, te, and(p1("助詞"), s("から")))),
        Rule(pt("te-miru", "～てみる", "N4", "Tentar fazer algo para ver como é.", "この料理を食べてみた。"),
            listOf(renyou, te, b("みる"))),
        Rule(pt("te-shimau", "～てしまう", "N4", "Ação concluída por completo, muitas vezes com pesar ou sem querer.", "財布を忘れてしまった。"),
            listOf(renyou, te, b("しまう", "ちゃう"))),
        Rule(pt("te-oku", "～ておく", "N4", "Fazer algo com antecedência, como preparação.", "飲み物を買っておく。"),
            listOf(renyou, te, b("おく", "とく"))),
        Rule(pt("te-aru", "～てある", "N4", "Estado resultante de uma ação feita de propósito por alguém.", "窓が開けてある。"),
            listOf(renyou, te, b("ある"))),
        Rule(pt("te-kuru", "～てくる", "N4", "Mudança ou ação que vem em direção ao falante, ou que começou no passado e continua.", "だんだん寒くなってきた。"),
            listOf(renyou, te, b("くる", "来る"))),
        Rule(pt("te-iku", "～ていく", "N4", "Ação ou mudança que se afasta do falante, ou que continua para o futuro.", "これからも勉強していく。"),
            listOf(renyou, te, b("いく", "行く"))),
        Rule(pt("te-ageru", "～てあげる／くれる／もらう", "N4", "Fazer um favor (あげる), receber um favor de alguém (くれる) ou obter que alguém faça (もらう).", "友達が手伝ってくれた。"),
            listOf(renyou, te, b("あげる", "くれる", "もらう", "くださる", "いただく", "やる"))),
        Rule(pt("tai", "～たい", "N5", "Desejo do falante: \"quero…\". 食べる → 食べたい.", "日本に行きたい。"),
            listOf(renyou, and(aux, b("たい")))),
        Rule(pt("tai-desu", "～たいです", "N5", "Desejo em forma polida.", "寿司が食べたいです。"),
            listOf(renyou, and(aux, b("たい"), cf("基本形")), and(aux, b("です")))),
        Rule(pt("rareru", "～れる／られる (passivo・potencial)", "N4", "Passivo (\"ser feito\") ou, em verbos ichidan, também potencial (\"poder fazer\").", "先生に褒められた。"),
            listOf(mizen, and(verb, b("れる", "られる")))),
        Rule(pt("saseru", "～せる／させる (causativo)", "N4", "Fazer ou deixar alguém fazer algo.", "子供に野菜を食べさせる。"),
            listOf(mizen, and(verb, b("せる", "させる")))),
        Rule(pt("you", "～う／よう (volitivo)", "N4", "\"Vamos…\" ou intenção: \"vou…\".", "一緒に行こう。"),
            listOf(and(verb, cf("未然ウ接続")), and(aux, b("う", "よう")))),
        Rule(pt("you-to-omou", "～ようと思う", "N4", "Intenção: \"estou pensando em…\".", "来年日本に行こうと思う。"),
            listOf(and(verb, cf("未然ウ接続")), and(aux, b("う", "よう")), and(p1("助詞"), s("と")), b("思う"))),
        Rule(pt("ba", "～ば (condicional)", "N4", "Condição: \"se…\". 行く → 行けば, 高い → 高ければ.", "安ければ買う。"),
            listOf(or(and(verb, cf("仮定形")), and(adjI, cf("仮定形")), and(aux, cf("仮定形"))), and(p1("助詞"), s("ば")))),
        Rule(pt("tara", "～たら (condicional)", "N4", "Condição ou \"quando/depois que\": 食べたら, 着いたら.", "雨が降ったら行かない。"),
            listOf(or(renyou, and(aux, cf("連用タ接続")), and(adjI, cf("連用タ接続"))), taSurf, and(p1("助詞"), s("ら")))),
        Rule(pt("nara", "～なら", "N4", "\"Se é o caso de…\", condição baseada no que o outro disse.", "日本語なら少し分かる。"),
            listOf(and(aux, b("だ"), s("なら")))),
        Rule(pt("nakereba", "～なければならない／いけない", "N4", "Obrigação: \"tenho que…\".", "明日早く起きなければならない。"),
            listOf(mizen, and(nai, cf("仮定形")), and(p1("助詞"), s("ば")), b("なる", "いける"), nai)),
        Rule(pt("nakute-mo-ii", "～なくてもいい", "N4", "Não é necessário: \"não precisa…\".", "急がなくてもいい。"),
            listOf(mizen, and(nai, s("なく")), and(p1("助詞"), s("て")), and(p1("助詞"), s("も")), or(b("いい", "よい", "良い"), s("いい")))),
        Rule(pt("naide", "～ないで", "N4", "\"Sem fazer…\" ou pedido negativo com ください.", "朝ご飯を食べないで来た。"),
            listOf(mizen, and(nai, cf("基本形")), and(p1("助詞"), s("で")))),
        Rule(pt("nagara", "～ながら", "N4", "Duas ações ao mesmo tempo: \"enquanto…\".", "音楽を聞きながら勉強する。"),
            listOf(renyou, and(p1("助詞"), s("ながら")))),
        Rule(pt("tari", "～たり～たりする", "N4", "Lista de ações como exemplos: \"faço coisas como A e B\".", "休みの日は本を読んだり寝たりする。"),
            listOf(or(renyou, and(aux, cf("連用タ接続"))), and(p1("助詞"), s("たり", "だり")))),
        Rule(pt("koto-ga-aru", "～たことがある", "N4", "Experiência: \"já fiz… alguma vez\".", "富士山に登ったことがある。"),
            listOf(or(renyou, and(aux, cf("連用タ接続"))), taSurf, and(noun, s("こと")), and(p1("助詞"), s("が")), b("ある"))),
        Rule(pt("koto-ga-dekiru", "～ことができる", "N4", "Capacidade ou possibilidade: \"consigo…\".", "漢字を読むことができる。"),
            listOf(plain, and(noun, s("こと")), and(p1("助詞"), s("が")), b("できる"))),
        Rule(pt("koto-ni-suru", "～ことにする", "N3", "Decisão do falante: \"decidi…\".", "毎日走ることにした。"),
            listOf(plain, and(noun, s("こと")), and(p1("助詞"), s("に")), b("する"))),
        Rule(pt("koto-ni-naru", "～ことになる", "N3", "Decisão tomada por outros ou pelas circunstâncias: \"ficou decidido que…\".", "大阪に転勤することになった。"),
            listOf(plain, and(noun, s("こと")), and(p1("助詞"), s("に")), b("なる"))),
        Rule(pt("you-ni-suru", "～ようにする", "N3", "Esforçar-se para fazer algo um hábito.", "野菜を食べるようにしている。"),
            listOf(plain, and(noun, s("よう")), and(p1("助詞"), s("に")), b("する"))),
        Rule(pt("you-ni-naru", "～ようになる", "N3", "Mudança gradual: \"passei a conseguir / passei a fazer\".", "日本語が話せるようになった。"),
            listOf(plain, and(noun, s("よう")), and(p1("助詞"), s("に")), b("なる"))),
        Rule(pt("tsumori", "～つもり", "N4", "Intenção planejada: \"pretendo…\".", "来年結婚するつもりだ。"),
            listOf(plain, and(noun, s("つもり")))),
        Rule(pt("hazu", "～はず", "N3", "Expectativa lógica: \"deve ser que…\".", "彼はもう着いているはずだ。"),
            listOf(or(plain, and(noun, cf("*"))), and(noun, s("はず")))),
        Rule(pt("kamoshirenai", "～かもしれない", "N4", "Possibilidade: \"talvez…\".", "明日は雨かもしれない。"),
            listOf(and(p1("助詞"), s("か")), and(p1("助詞"), s("も")), b("しれる", "知れる"), nai)),
        Rule(pt("kamoshirenai", "～かもしれない", "N4", "Possibilidade: \"talvez…\".", "明日は雨かもしれない。"),
            listOf(and(p1("助詞"), s("かも")), b("しれる", "知れる"), nai)),
        Rule(pt("deshou", "～でしょう", "N4", "Probabilidade ou busca de concordância: \"provavelmente…\", \"não é?\".", "明日は晴れるでしょう。"),
            listOf(and(aux, b("です"), s("でしょ")), and(aux, s("う")))),
        Rule(pt("to-omou", "～と思う", "N5", "Opinião do falante: \"acho que…\".", "この本は面白いと思う。"),
            listOf(and(p1("助詞"), s("と")), b("思う"))),
        Rule(pt("to-iu", "～と言う／という", "N5", "Citar o que alguém disse, ou \"chamado de…\".", "田中さんは来ないと言った。"),
            listOf(and(p1("助詞"), s("と")), b("言う", "いう"))),
        Rule(pt("sou-looks", "～そう (parece)", "N4", "Pela aparência: \"parece que…\". Liga-se ao radical: おいしそう, 降りそう.", "このケーキはおいしそうだ。"),
            listOf(or(renyou, and(adjI, cf("ガル接続")), and(noun, p2("形容動詞語幹"))), and(p1("名詞"), s("そう")))),
        Rule(pt("sou-hearsay", "～そうだ (dizem que)", "N4", "Informação de segunda mão: \"ouvi dizer que…\". Liga-se à forma plana: 降るそうだ.", "明日は雪が降るそうだ。"),
            listOf(plain, and(p1("名詞"), s("そう")), and(aux, b("だ", "です")))),
        Rule(pt("rashii", "～らしい", "N3", "\"Parece que…\" com base em indícios, ou \"típico de\".", "彼は忙しいらしい。"),
            listOf(and(aux, b("らしい")))),
        Rule(pt("mitai", "～みたい", "N3", "\"Parece…\", \"como se…\", informal.", "子供みたいだ。"),
            listOf(and(aux, b("みたい")))),
        Rule(pt("you-da", "～ようだ", "N3", "\"Parece que…\", conclusão do falante, mais formal que みたい.", "誰もいないようだ。"),
            listOf(and(noun, s("よう")), and(aux, b("だ", "です")))),
        Rule(pt("sugiru", "～すぎる", "N4", "Excesso: \"…demais\". 食べすぎる, 高すぎる.", "昨日は食べすぎた。"),
            listOf(or(renyou, and(adjI, cf("ガル接続"))), b("すぎる", "過ぎる"))),
        Rule(pt("yasui", "～やすい／にくい", "N4", "Fácil de fazer (やすい) ou difícil de fazer (にくい).", "この字は読みにくい。"),
            listOf(renyou, b("やすい", "にくい"))),
        Rule(pt("hajimeru", "～始める／終わる／続ける", "N4", "Começar, terminar ou continuar uma ação.", "本を読み始めた。"),
            listOf(renyou, b("始める", "終わる", "続ける", "はじめる", "おわる", "つづける"))),
        Rule(pt("kata", "～方 (modo de)", "N4", "O modo de fazer: 読み方 (modo de ler), 使い方 (modo de usar).", "この漢字の読み方が分からない。"),
            listOf(renyou, and(noun, s("方"), p2("接尾")))),
        Rule(pt("n-desu", "～んです／のです", "N4", "Explicação ou pedido de explicação: \"é que…\".", "頭が痛いんです。"),
            listOf(and(noun, s("ん", "の"), p2("非自立")), and(aux, b("です", "だ")))),
        Rule(pt("node", "～ので", "N4", "Causa, mais suave e objetiva que から.", "雨なので行かない。"),
            listOf(and(p1("助詞"), s("ので")))),
        Rule(pt("noni", "～のに", "N4", "Contraste com surpresa ou frustração: \"apesar de…\".", "頑張ったのに落ちた。"),
            listOf(and(p1("助詞"), s("のに")))),
        Rule(pt("kara", "～から (porque)", "N5", "Causa ou razão: \"porque…\".", "忙しいから行けない。"),
            listOf(or(plain, and(aux, cf("基本形"))), and(p1("助詞"), p2("接続助詞"), s("から")))),
        Rule(pt("kedo", "～けど／けれども", "N5", "\"Mas…\" ou um preâmbulo suave.", "行きたいけど時間がない。"),
            listOf(and(p1("助詞"), s("けど", "けれど", "けれども")))),
        Rule(pt("shi", "～し", "N4", "Lista de razões: \"e ainda por cima…\".", "安いし、おいしいし、また来たい。"),
            listOf(and(p1("助詞"), p2("接続助詞"), s("し")))),
        Rule(pt("temo", "～ても／でも", "N4", "Concessão: \"mesmo que…\".", "雨が降っても行く。"),
            listOf(renyou, te, and(p1("助詞"), s("も")))),
        Rule(pt("mae-ni", "～前に", "N4", "\"Antes de…\" (verbo na forma de dicionário).", "寝る前に歯を磨く。"),
            listOf(plain, and(noun, s("前")), and(p1("助詞"), s("に")))),
        Rule(pt("ato-de", "～た後で", "N4", "\"Depois de…\" (verbo no passado).", "食べた後で散歩する。"),
            listOf(taSurf, and(noun, s("後", "あと")), and(p1("助詞"), s("で")))),
        Rule(pt("toki", "～とき", "N4", "\"Quando…\": momento em que algo acontece.", "日本に行ったとき、寿司を食べた。"),
            listOf(or(plain, taSurf, adjI), and(noun, s("とき", "時")))),
        Rule(pt("tame-ni", "～ために", "N4", "Finalidade (\"para…\") ou causa (\"por causa de…\").", "試験のために勉強する。"),
            listOf(and(noun, s("ため")), and(p1("助詞"), s("に")))),
        Rule(pt("hou-ga-ii", "～ほうがいい", "N4", "Conselho: \"é melhor…\". Com た para afirmativo, ない para negativo.", "早く寝たほうがいい。"),
            listOf(and(noun, s("ほう", "方")), and(p1("助詞"), s("が")), or(b("いい", "よい", "良い"), s("いい")))),
        Rule(pt("shika-nai", "～しか～ない", "N4", "\"Só…\" com sentido de insuficiência.", "百円しかない。"),
            listOf(and(p1("助詞"), s("しか")))),
        Rule(pt("dake", "～だけ", "N5", "\"Só, apenas\".", "一つだけください。"),
            listOf(and(p1("助詞"), s("だけ")))),
        Rule(pt("bakari", "～ばかり", "N3", "\"Só faz…\" (excesso) ou \"acabou de…\" (～たばかり).", "ゲームばかりしている。"),
            listOf(and(p1("助詞"), s("ばかり")))),
        Rule(pt("ta-bakari", "～たばかり", "N3", "\"Acabou de…\".", "今起きたばかりだ。"),
            listOf(taSurf, and(p1("助詞"), s("ばかり")))),
        Rule(pt("tokoro", "～ところ", "N3", "Momento da ação: ～るところ (vai fazer), ～ているところ (fazendo), ～たところ (acabou de).", "今出かけるところだ。"),
            listOf(or(plain, taSurf), and(noun, s("ところ")))),
        Rule(pt("aida-ni", "～間に", "N3", "\"Enquanto…\", durante um intervalo.", "寝ている間に雨が降った。"),
            listOf(and(noun, s("間")), and(p1("助詞"), s("に")))),
        Rule(pt("yori", "～より", "N5", "Comparação: \"do que…\".", "電車よりバスのほうが安い。"),
            listOf(and(p1("助詞"), s("より")))),
        Rule(pt("hodo", "～ほど", "N3", "\"Tanto quanto\" (negativo) ou \"a ponto de\".", "今日は昨日ほど寒くない。"),
            listOf(and(p1("助詞"), s("ほど")))),
        Rule(pt("ka-dou-ka", "～かどうか", "N4", "\"Se… ou não\".", "行くかどうか分からない。"),
            listOf(and(p1("助詞"), s("か")), s("どう"), and(p1("助詞"), s("か")))),
        Rule(pt("beki", "～べき", "N3", "Dever moral ou recomendação forte: \"deveria…\".", "約束は守るべきだ。"),
            listOf(and(aux, b("べき", "べし")))),
        Rule(pt("desu", "～です (cópula polida)", "N5", "\"É\" em registro educado, com substantivos e adjetivos.", "私は学生です。"),
            listOf(and(aux, b("です"), cf("基本形")))),
        Rule(pt("deshita", "～でした", "N5", "Passado da cópula polida: \"era\".", "昨日は休みでした。"),
            listOf(and(aux, b("です"), cf("連用形")), ta)),
        Rule(pt("ja-nai", "～じゃない／ではない", "N5", "Negativo da cópula: \"não é\".", "これは私のじゃない。"),
            listOf(and(aux, b("だ"), s("じゃ", "では")), nai)),
        Rule(pt("ne", "～ね", "N5", "Busca concordância ou suaviza: \"né?\".", "いい天気ですね。"),
            listOf(and(p1("助詞"), p2("終助詞"), s("ね")))),
        Rule(pt("yo", "～よ", "N5", "Dá ênfase ou informa algo que o outro não sabe.", "この店はおいしいよ。"),
            listOf(and(p1("助詞"), p2("終助詞"), s("よ")))),
        Rule(pt("ka", "～か (pergunta)", "N5", "Marca a pergunta no fim da frase.", "日本人ですか。"),
            listOf(and(p1("助詞"), s("か")), and(p1("記号"), s("。", "？", "?")))),
        Rule(pt("adj-kunai", "～くない (adjetivo-i negativo)", "N5", "Negativo de adjetivo-i: 高い → 高くない.", "この問題は難しくない。"),
            listOf(and(adjI, cf("連用テ接続")), and(nai, cf("基本形")))),
        Rule(pt("adj-katta", "～かった (adjetivo-i passado)", "N5", "Passado de adjetivo-i: 楽しい → 楽しかった.", "旅行は楽しかった。"),
            listOf(and(adjI, cf("連用タ接続")), taSurf)),
        Rule(pt("adj-ku-naru", "～くなる／～になる", "N4", "Mudança de estado: \"ficar…\". 寒くなる, 静かになる.", "だんだん暖かくなる。"),
            listOf(or(and(adjI, cf("連用テ接続")), and(p1("助詞"), s("に"))), b("なる"))),
    )

    // ---------- detecção ----------

    private val subsumes: List<Pair<String, List<String>>> = listOf(
        "mashita" to listOf("masu", "ta"),
        "masen" to listOf("masu"),
        "masenka" to listOf("masen", "masu", "ka"),
        "mashou" to listOf("masu"),
        "nakatta" to listOf("nai", "ta"),
        "tai-desu" to listOf("tai", "desu"),
        "you-to-omou" to listOf("you", "to-omou"),
        "deshita" to listOf("desu", "ta"),
        "koto-ga-aru" to listOf("ta"),
        "ta-bakari" to listOf("ta", "bakari"),
        "nakereba" to listOf("nai", "ba"),
        "nakute-mo-ii" to listOf("nai"),
        "naide" to listOf("nai"),
        "te-mo-ii" to listOf("temo"),
        "tara" to listOf("ta"),
        "ato-de" to listOf("ta"),
        "adj-katta" to listOf("ta"),
        "adj-kunai" to listOf("nai"),
        "ja-nai" to listOf("nai"),
        "kamoshirenai" to listOf("nai"),
        "sou-hearsay" to listOf("desu"),
        "you-da" to listOf("desu"),
        "n-desu" to listOf("desu"),
        "hou-ga-ii" to listOf("ta"),
    )

    private val pointsById: Map<String, Point> = rules.associate { it.point.id to it.point }

    fun point(id: String): Point? = pointsById[id]

    /** Pontos gramaticais encontrados na frase, sem repetir o mesmo ponto. */
    fun find(sentence: String): List<Hit> {
        val tokens = try { Jp.tokenize(sentence) } catch (_: Exception) { return emptyList() }
        val hits = mutableListOf<Hit>()
        val seen = HashSet<String>()
        for (rule in rules) {
            val n = rule.steps.size
            var i = 0
            while (i + n <= tokens.size) {
                var ok = true
                for (k in 0 until n) if (!rule.steps[k](tokens[i + k])) { ok = false; break }
                if (ok) {
                    val first = tokens[i]
                    val last = tokens[i + n - 1]
                    if (seen.add(rule.point.id)) {
                        var end = last.position + last.surface.length
                        // Não inclui pontuação final no trecho do padrão
                        while (end > first.position && sentence[end - 1] in "。、？！?!") end--
                        hits.add(Hit(rule.point, first.position, end))
                    }
                    i += n
                } else i++
            }
        }
        // Padrões contidos em outros (～ます dentro de ～ました) não aparecem separados
        val present = hits.map { it.point.id }.toSet()
        val hidden = HashSet<String>()
        for ((bigger, smaller) in subsumes) if (bigger in present) hidden.addAll(smaller)
        return hits.filter { it.point.id !in hidden }.sortedBy { it.start }
    }

    /** Todos os pontos do catálogo (para alternativas erradas em exercícios). */
    val all: List<Point> get() = rules.map { it.point }
}
