package com.keveny.legendajp

/**
 * Listas de kanji por nível JLPT (N5, N4, N3, N2). Kanji fora das listas contam como N1.
 * As listas seguem as compilações mais comuns do JLPT; pequenas divergências entre fontes
 * só mudam a prioridade do exercício, não o conteúdo.
 */
object Jlpt {
    private const val N5 = "一二三四五六七八九十百千万円日月火水木金土曜年時分半間今先毎週何人男女子父母友学生校先生山川田白上下中大小高安新古長多少早好元気食飲見聞話読書買行来帰出入休立歩右左東西南北外前後午名国語本車電話天雨道店会社"

    private const val N4 = "悪暗医意以引員飲運映英駅遠音屋夏家歌画回海開界楽漢館帰起急牛去魚京強教業近銀空兄計建犬験言元光考口工広黒合今作産紙思試止持事自字室質写者借弱主首秋習集住終春書所暑少場色心森真親進図世正青切説赤昼注着鳥朝通弟低転度冬答動同堂特働肉売発飯病品不風服物文別勉方妹味民明目門問夜野洋用理旅料力林和使始仕死私次治重代題短知地池茶町直都投頭反有"

    private const val N3 = "政議民連対部合市内相定回選米実関決全表戦経最現調化当約首法性的要制治務成期取都和機平加受続進数記初指権支産点報済活原共得解交資予向際勝面告反判認参利組信在件側任引求所次昨論官増係感情投示変打直両式確果容必演歳争談能位置流格疑過局放常状球職与供役構割費付由説難優夫収断石違消神番規術備宅害配警育席訪乗残想声念助労例然限追商葉伝働形景落好退頭負渡失差末守若種美命福望非観察段横深申様財港識呼達良阪候程満敗値突光路科積他処太客否師登易速存飛殺号単座破除完降責捕危給苦迎園具辞因馬愛富彼未舞亡冷適婦寄込顔類余王返妻背熱宿薬険頼覚船途許抜便留罪努精散静婚喜浮絶幸押倒等老曲払庭徒勤遅居雑招困欠更刻賛抱犯恐息遠戸地偉夢床浅欲"

    private const val N2 = "党協総区領県設改府査委軍団各島革村勢減再税営比防補境導副算輸述線農州武象域額欧担準賞辺造被技低復移個門課脳極含蔵量型況針専谷史階管兵接細効丸湾録省旧橋岸周材戸央券編捜竹超並評検草独企兆宇拡展印刷乱採黄株厚札傾準盤替値張案署混鉄拡模緑整"

    private val n5 = N5.toSet()
    private val n4 = N4.toSet()
    private val n3 = N3.toSet()
    private val n2 = N2.toSet()

    /** Nível do kanji: 5, 4, 3, 2 ou 1 (fora das listas). */
    fun kanjiLevel(c: Char): Int = when {
        c in n5 -> 5
        c in n4 -> 4
        c in n3 -> 3
        c in n2 -> 2
        else -> 1
    }

    /** Nível de uma palavra pelos kanji que ela tem (o mais difícil manda). Sem kanji: 5. */
    fun wordLevel(word: String): Int {
        var lvl = 5
        for (c in word) if (StudyGen.isKanji(c)) lvl = minOf(lvl, kanjiLevel(c))
        return lvl
    }
}
