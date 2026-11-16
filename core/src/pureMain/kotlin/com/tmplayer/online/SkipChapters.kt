package com.tmplayer.online

import com.tmplayer.player.Chapter

/**
 * Skippable stretches named by a file's own chapters ("Intro", "Opening", "Recap", "Credits",
 * "Ending", "Preview" and their words in other languages): free, exact, and offline, so they win
 * over any online database (see [SkipMerge]).
 *
 * The mapping is a pure function of the title: lower case, accents folded, everything that is
 * not a letter or a digit a space, then looked up as whole words (or as a substring for the
 * scripts written without spaces). A chapter title is somebody's free text, so a title that
 * matches nothing is simply not a skip: the cost of a miss is a missing button, the cost of a
 * false hit is a button over the story, so the table is conservative and a chapter that is too
 * short or too long to be one ([segments]) is dropped even when its name fits.
 */
object ChapterSkips {

    /** What [title] names, or null for a chapter that is not a skippable stretch. */
    fun kindOf(title: String?): SkipKind? {
        if (title.isNullOrBlank()) return null
        // Free text: a title that long is a sentence, not a chapter name.
        val text = fold(title.take(MAX_TITLE))
        if (text.isBlank()) return null
        val padded = " ${spaced(text)} "
        if (NOT_A_SKIP.any { padded.contains(" $it ") || (it.isUnspaced() && text.contains(it)) }) return null
        codeKind(spaced(text))?.let { return it }
        for (rule in RULES) {
            if (rule.unspaced) {
                if (text.contains(rule.phrase)) return rule.kind
            } else if (padded.contains(rule.spacedPhrase)) {
                return rule.kind
            }
        }
        return null
    }

    /**
     * The stretches [chapters] name, each running to the next chapter's start (or the end of the
     * file, [durationMs], when known). A stretch under five seconds, over six minutes (ten for
     * credits) or over two fifths of the file is not one; neither is a skip that has no end and
     * is not credits. Chapters need not come sorted; a start before zero or past the file is ignored.
     */
    fun segments(chapters: List<Chapter>, durationMs: Long): List<SkipSegment> {
        val sorted = chapters.filter { it.startMs >= 0 && (durationMs <= 0 || it.startMs < durationMs) }
            .sortedBy { it.startMs }
        val out = ArrayList<SkipSegment>()
        sorted.forEachIndexed { i, chapter ->
            val kind = kindOf(chapter.title) ?: return@forEachIndexed
            val next = sorted.getOrNull(i + 1)?.startMs?.takeIf { it > chapter.startMs }
            val end = next ?: durationMs.takeIf { it > chapter.startMs }
            val closing = kind == SkipKind.Credits || kind == SkipKind.Ending
            if (end == null) {
                if (closing) out += SkipSegment(kind, chapter.startMs, null, SkipSource.Chapter)
                return@forEachIndexed
            }
            val length = end - chapter.startMs
            val longest = if (closing) CLOSING_MAX_MS else OPENING_MAX_MS
            if (length < MIN_MS || length > longest) return@forEachIndexed
            if (durationMs > 0 && length * 5 > durationMs * 2) return@forEachIndexed
            out += SkipSegment(kind, chapter.startMs, end, SkipSource.Chapter)
        }
        return out
    }

    const val MIN_MS = 5_000L
    const val OPENING_MAX_MS = 6 * 60_000L
    const val CLOSING_MAX_MS = 10 * 60_000L
    private const val MAX_TITLE = 120

    private class Rule(val phrase: String, val kind: SkipKind) {
        val unspaced = phrase.isUnspaced()
        val spacedPhrase = " $phrase "
    }

    /** Whole titles that are only a code: OP, ED2, NCOP, NCED1. */
    private val CODE = Regex("""(nc)?(op|ed)( ?\d{1,2}[a-z]?)?""")

    private fun codeKind(spacedText: String): SkipKind? {
        val m = CODE.matchEntire(spacedText.trim()) ?: return null
        return if (m.groupValues[2] == "op") SkipKind.Opening else SkipKind.Ending
    }

    private fun String.isUnspaced(): Boolean = any {
        val c = it.code
        c in 0x3040..0x30FF || c in 0x3400..0x9FFF || c in 0xAC00..0xD7AF || c in 0x0E00..0x0E7F || c in 0xF900..0xFAFF
    }

    private fun spaced(text: String): String {
        val b = StringBuilder(text.length)
        var gap = true
        for (c in text) {
            if (c.isLetterOrDigit()) {
                b.append(c)
                gap = false
            } else if (!gap) {
                b.append(' ')
                gap = true
            }
        }
        return b.toString().trim()
    }

    private val FOLDS = mapOf(
        'à' to 'a', 'á' to 'a', 'â' to 'a', 'ã' to 'a', 'ä' to 'a', 'å' to 'a', 'ā' to 'a', 'ą' to 'a',
        'ç' to 'c', 'ć' to 'c', 'č' to 'c', 'ď' to 'd', 'ð' to 'd',
        'è' to 'e', 'é' to 'e', 'ê' to 'e', 'ë' to 'e', 'ē' to 'e', 'ę' to 'e', 'ě' to 'e',
        'ì' to 'i', 'í' to 'i', 'î' to 'i', 'ï' to 'i', 'ī' to 'i', 'ı' to 'i', 'ğ' to 'g',
        'ł' to 'l', 'ñ' to 'n', 'ń' to 'n', 'ň' to 'n',
        'ò' to 'o', 'ó' to 'o', 'ô' to 'o', 'õ' to 'o', 'ö' to 'o', 'ø' to 'o', 'ō' to 'o', 'ő' to 'o',
        'ř' to 'r', 'ś' to 's', 'š' to 's', 'ş' to 's', 'ť' to 't',
        'ù' to 'u', 'ú' to 'u', 'û' to 'u', 'ü' to 'u', 'ū' to 'u', 'ů' to 'u', 'ű' to 'u',
        'ý' to 'y', 'ÿ' to 'y', 'ź' to 'z', 'ż' to 'z', 'ž' to 'z', 'ё' to 'е',
    )

    /** Lower case with the accents of the Latin scripts folded; other scripts keep their letters. */
    internal fun fold(text: String): String {
        val b = StringBuilder(text.length)
        for (c in text.lowercase()) {
            when (c) {
                'ß' -> b.append("ss")
                'æ' -> b.append("ae")
                'œ' -> b.append("oe")
                // A combining accent left over from a decomposed letter.
                in '̀'..'ͯ' -> Unit
                else -> b.append(FOLDS[c] ?: c)
            }
        }
        return b.toString()
    }

    /** Words that make a title a scene or a talk about the thing, not the thing. */
    private val NOT_A_SKIP: List<String> = listOf(
        "scene", "scenes", "escena", "escenas", "scena", "scene", "szene", "szenen", "cena", "cenas",
        "сцена", "сцены", "场景", "場景", "シーン", "장면", "speech", "ceremony",
    ).map { fold(it) }

    private fun r(kind: SkipKind, vararg phrases: String): List<Rule> = phrases.map { Rule(fold(it), kind) }

    /**
     * In the order they are tried, most specific first: "opening credits" is an opening, not
     * credits; "end credits" and "ending" are the end; "next time" is a preview, not "time".
     */
    private val RULES: List<Rule> =
        r(
            SkipKind.Preview,
            "preview", "previews", "next time", "next episode", "next on", "coming up", "coming next", "on the next",
            "avance", "proximo capitulo", "proximo episodio", "proximamente", "adelanto", "avance del proximo capitulo",
            "prochainement", "prochain episode", "episode suivant", "a suivre", "bande annonce de l episode",
            "vorschau", "nachste folge", "nachste episode", "folgenvorschau",
            "anteprima", "prossimamente", "prossima puntata", "nella prossima puntata", "prossimo episodio",
            "previa", "proximo episodio", "no proximo episodio",
            "volgende week", "volgende aflevering", "volgende keer",
            "анонс", "в следующей серии", "следующая серия", "наступна серія", "наступна серія",
            "zapowiedz", "w nastepnym odcinku", "nastepny odcinek",
            "sonraki bolum", "bir sonraki bolum", "fragman", "gelecek bolum",
            "次回予告", "予告", "次回", "预告", "預告", "下集预告", "下集預告", "下期预告", "下集",
            "예고", "다음 회", "다음화", "다음 화",
            "الحلقة القادمة", "الحلقه القادمه", "الحلقة التالية", "حلقة قادمة",
            "nasta avsnitt", "naeste afsnit", "neste episode", "pristi dil", "pristi epizoda",
            "episode berikutnya", "cuplikan", "tap tiep theo", "tập tiếp theo", "xem trước", "tiếp theo",
        ) + r(
            SkipKind.Recap,
            "recap", "previously", "previously on", "last time", "story so far", "the story so far", "last week on",
            "resumen", "previamente", "anteriormente", "capitulos anteriores", "en capitulos anteriores", "anteriormente en",
            "resume", "precedemment", "rappel", "episodes precedents", "dans les episodes precedents", "dans l episode precedent",
            "ruckblick", "zuvor", "bisher", "was bisher geschah", "vorher", "zusammenfassung", "rueckblick",
            "riassunto", "precedentemente", "nelle puntate precedenti", "in precedenza", "riepilogo", "negli episodi precedenti",
            "resumo", "recapitulacao", "nos episodios anteriores", "episodios anteriores",
            "eerder", "voorgaande", "vorige keer", "samenvatting", "eerder gezien",
            "рекап", "ранее", "в предыдущих сериях", "краткое содержание", "напоминание", "раніше", "попередньо", "попередні серії",
            "poprzednio", "wczesniej", "w poprzednich odcinkach", "streszczenie",
            "onceki bolum", "oncekiler", "ozet", "gecen bolum", "onceki bolumler",
            "前回のあらすじ", "前回", "あらすじ", "振り返り", "前情提要", "前情回顾", "前情回顧", "上集回顾", "上集回顧", "回顾", "回顧", "前情",
            "지난 이야기", "이전 이야기", "지난 줄거리", "요약", "줄거리",
            "ملخص", "سابقا", "ما حدث سابقا", "في الحلقات السابقة", "الحلقات السابقة",
            "tidigare", "forra avsnittet", "forrige afsnit", "minule", "shrnuti",
            "sebelumnya", "ringkasan", "tom tat", "tóm tắt", "tập trước", "trước đó",
        ) + r(
            // Before the closing words, which they contain.
            SkipKind.Opening,
            "opening credits", "opening titles", "opening sequence", "opening theme", "title sequence", "main titles",
            "オープニング", "오프닝", "opening", "opening song", "opening animation",
            "опенинг", "опенінг",
        ) + r(
            SkipKind.Ending,
            "ending theme", "ending song", "ending sequence", "end theme", "ending",
            "エンディング", "엔딩", "эндинг",
        ) + r(
            SkipKind.Credits,
            "credits", "end credits", "closing credits", "end titles", "closing titles", "end roll", "rolling credits", "outro",
            "creditos", "creditos finales", "creditos de cierre", "creditos finais", "encerramento", "encerramento do episodio",
            "generique de fin", "generique fin", "credits de fin", "fin generique",
            "abspann", "nachspann",
            "titoli di coda", "crediti",
            "aftiteling", "aftitels", "eindgeneriek",
            "титры", "финальные титры", "конечные титры", "кредиты", "титри", "фінальні титри",
            "napisy koncowe",
            "kapanis jenerigi", "bitis jenerigi", "kapanis",
            "クレジット", "エンドクレジット", "スタッフロール", "エンドロール",
            "片尾", "片尾曲", "片尾字幕", "演职员表", "演職員表", "制作人员", "製作人員", "片尾名单",
            "크레딧", "엔딩 크레딧",
            "الختام", "النهاية", "شارة النهاية", "الاعتمادات", "شارة الختام",
            "eftertexter", "sluttext", "rulletekster", "eftertekster", "sluttekster", "zaverecne titulky",
            "kredit", "kredit akhir", "penutup", "ket thuc", "kết thúc",
        ) + r(
            SkipKind.Intro,
            "intro", "intro sequence", "title card sequence",
            "cabecera", "apertura", "cortinilla", "intro de la serie",
            "generique", "generique de debut", "generique d ouverture",
            "vorspann", "titelmusik", "titelsong",
            "sigla", "sigla iniziale", "titoli di testa",
            "abertura", "vinheta de abertura",
            "begingeneriek", "intromuziek", "begintune",
            "заставка", "открывающая заставка", "интро", "інтро",
            "czolowka",
            "acilis jenerigi", "giris jenerigi", "acilis",
            "片头", "片頭", "片头曲", "片頭曲", "片头动画", "开场", "開場",
            "인트로",
            "المقدمة", "مقدمة", "شارة البداية", "شارة المقدمة",
            "znelka", "uvodni znelka",
            "pembuka", "pembukaan", "mo dau", "mở đầu", "nhac mo dau",
        )
}
