package com.minim.music.data.ai

/**
 * 5.6.0: which lyric lines need an English translation.
 *
 * Runs on the phone with no network, so the translate button can hide itself for English songs
 * before anything is sent to Gemini. Each line is one of:
 * - [Kind.SKIP]: nothing to translate (empty, only ♪ or symbols, "Instrumental", "(Chorus)",
 *   or only vocal sounds such as "oh oh" and "la la la").
 * - [Kind.ENGLISH]: already English. No translation is shown under it.
 * - [Kind.OTHER]: another language, written in its own script (Devanagari, Hangul, Arabic, ...)
 *   or in Latin letters (Hinglish, Spanish, romanized Korean, ...).
 *
 * The English test counts words from a list of common English (and common English lyric) words
 * against words from a list of common romanized Hindi/Urdu/Punjabi, Spanish, Portuguese, Korean and
 * Japanese words. Words in neither list count a little against English, so a line of only unknown
 * words ("Kesariya tera") is treated as another language.
 */
object LyricLanguage {

    enum class Kind { SKIP, ENGLISH, OTHER }

    /** What a whole song needs. [kinds] has one entry per lyric line, in order. */
    data class Plan(val kinds: List<Kind>) {
        val otherCount: Int get() = kinds.count { it == Kind.OTHER }
        val textCount: Int get() = kinds.count { it != Kind.SKIP }
        /** Lines worth sending to Gemini: everything with words in it (English lines give context). */
        val sendIndices: List<Int> get() = kinds.indices.filter { kinds[it] != Kind.SKIP }

        /**
         * The translate button shows when the song isn't English: two or more lines in another
         * language, or one in a very short song. One stray line in a long English song (a name,
         * a single foreign word) doesn't count.
         */
        val showButton: Boolean
            get() = otherCount >= MIN_OTHER_LINES || (otherCount >= 1 && textCount <= SHORT_SONG_LINES)
    }

    const val MIN_OTHER_LINES = 2
    const val SHORT_SONG_LINES = 8

    fun plan(lines: List<String>): Plan = Plan(lines.map { kind(it) })

    fun kind(raw: String): Kind {
        val text = raw.trim()
        if (text.isEmpty()) return Kind.SKIP
        if (isMarker(text)) return Kind.SKIP
        var hasLetter = false
        for (ch in text) {
            if (!Character.isLetter(ch)) continue
            hasLetter = true
            val script = Character.UnicodeScript.of(ch.code)
            if (script != Character.UnicodeScript.LATIN &&
                script != Character.UnicodeScript.COMMON &&
                script != Character.UnicodeScript.INHERITED
            ) return Kind.OTHER
        }
        if (!hasLetter) return Kind.SKIP
        val words = words(text)
        if (words.isEmpty()) return Kind.SKIP
        var english = 0
        var other = 0
        var unknown = 0
        for (w in words) {
            when {
                w in VOCALS || w in NEUTRAL -> Unit
                w in OTHER_WORDS -> other++
                w in ENGLISH_WORDS || englishForm(w) -> english++
                hasAccent(w) -> other++
                else -> unknown++
            }
        }
        if (english == 0 && other == 0 && unknown == 0) {
            // Only vocal sounds ("oh oh oh", "na na na") or only ambiguous short words
            return if (words.all { it in VOCALS }) Kind.SKIP else Kind.OTHER
        }
        if (english == 0) return Kind.OTHER
        val score = english / (english + other + UNKNOWN_WEIGHT * unknown)
        return if (score >= ENGLISH_SCORE && other * 3 <= english) Kind.ENGLISH else Kind.OTHER
    }

    /**
     * Plainly English: every word is a known English word (at least two of them), none from
     * another language. A translation Gemini still sends for such a line is dropped.
     */
    fun isSurelyEnglish(raw: String): Boolean {
        if (kind(raw) != Kind.ENGLISH) return false
        val words = words(raw.trim()).filter { it !in VOCALS && it !in NEUTRAL }
        return words.size >= 2 && words.all { it in ENGLISH_WORDS || englishForm(it) }
    }

    private const val UNKNOWN_WEIGHT = 0.6
    private const val ENGLISH_SCORE = 0.62

    /** Lower case words: letters plus inner apostrophes (curly ones made straight). */
    internal fun words(text: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        fun flush() {
            val w = sb.toString().trim('\'')
            if (w.isNotEmpty()) out += w
            sb.setLength(0)
        }
        for (c in text) {
            when {
                Character.isLetter(c) -> sb.append(c.lowercaseChar())
                c == '\'' || c == '’' || c == '‘' -> if (sb.isNotEmpty()) sb.append('\'')
                else -> flush()
            }
        }
        flush()
        return out
    }

    /** "Instrumental", "[Chorus]", "(Music)", "♪" and friends. */
    private fun isMarker(text: String): Boolean {
        val t = text.lowercase().trim().trim('(', ')', '[', ']', '{', '}', '*', '-', '–', '—', ' ', ':', '.')
        if (t.isEmpty()) return true
        if (t.all { !Character.isLetterOrDigit(it) }) return true
        val core = t.filter { Character.isLetter(it) || it == ' ' }.trim()
        if (core in MARKERS) return true
        // "[Chorus: Arijit Singh]" / "[Verse 2]"
        val wrapped = text.trim().let { (it.startsWith("[") && it.endsWith("]")) || (it.startsWith("(") && it.endsWith(")")) }
        if (wrapped) {
            val first = core.substringBefore(' ')
            if (first in SECTION_WORDS) return true
        }
        return false
    }

    /** Simple English endings on a listed word: "loving", "dreams", "walked", "darkness". */
    private fun englishForm(w: String): Boolean {
        if (w.length < 4) return false
        for (suffix in SUFFIXES) {
            if (w.length > suffix.length + 2 && w.endsWith(suffix)) {
                val stem = w.dropLast(suffix.length)
                if (stem in ENGLISH_WORDS || (stem + "e") in ENGLISH_WORDS ||
                    (stem.length > 3 && stem[stem.length - 1] == stem[stem.length - 2] && stem.dropLast(1) in ENGLISH_WORDS)
                ) return true
            }
        }
        return false
    }

    private fun hasAccent(w: String): Boolean = w.any { it.code in 0xC0..0x24F }

    private val SUFFIXES = listOf("ing", "in'", "s", "es", "ed", "d", "ly", "ness", "er", "est", "y")

    private val MARKERS = setOf(
        "instrumental", "music", "interlude", "intro", "outro", "solo", "guitar solo", "break",
        "chorus", "verse", "bridge", "hook", "pre chorus", "prechorus", "refrain", "repeat",
        "applause", "silence", "humming", "music playing", "instrumental break", "beat", "beat drop",
    )

    private val SECTION_WORDS = setOf(
        "chorus", "verse", "bridge", "hook", "intro", "outro", "refrain", "pre", "prechorus",
        "interlude", "instrumental", "music", "repeat", "x", "post",
    )

    /** Sounds, not words: nothing to translate. */
    private val VOCALS = setOf(
        "oh", "ooh", "oooh", "ohh", "ohhh", "o", "ah", "aah", "ahh", "aa", "aaa", "la", "lala", "na", "nana",
        "yeah", "yeh", "yea", "ya", "yah", "hey", "hmm", "mm", "mmm", "hm", "uh", "uhh", "huh", "woah", "whoa",
        "wo", "woo", "wooh", "eh", "ay", "ayy", "aye", "ho", "ha", "haa", "da", "di", "dum", "ba", "doo",
        "shoo", "bop", "sha", "ra", "rara", "oho", "aha", "ahaa", "ohoho", "hoo", "hu", "le", "lo", "oi",
    )

    /** Short words that mean something in English and in another language too ("main", "me", "to"). */
    private val NEUTRAL = setOf(
        "main", "hum", "me", "to", "re", "hi", "so", "are", "ye", "pa", "ma", "mi", "bas", "yo", "sun", "sang", "rang", "pal",
    )

    private val ENGLISH_WORDS: Set<String> = (
        "a i about above across after again against ain't all almost alone along already alright also always am amazing " +
        "an and angel another any anymore anyone anything anyway arms around as ask at away baby back bad be beat " +
        "beautiful because become bed been before begin behind believe best better between big bit black blood blue body " +
        "born both boy brain break breath breathe bright bring broke broken brother burn burning but by call came can " +
        "can't cannot car care cause 'cause chance change chase city clear close cold come comes coming could couldn't " +
        "crazy cry cut dance dancing dark day days dead deep did didn't die different do does doesn't doing don't done " +
        "door down dream dreams drink drive drop drunk dust each easy eat else end enough even ever every everybody " +
        "everyone everything everywhere eye eyes face fade fall falling far fast feel feeling feelings feet fell felt " +
        "fight fill find fine fire first fly follow food for forever forget forgive found free friend friends from " +
        "front full fun game gave get gets getting girl give given go god goes going gold gone gonna good goodbye got " +
        "gotta ground grow had hand hands happy hard has hate have haven't having he he's head hear heard heart hearts " +
        "heaven hell hello help her here hide high him his hit hold home hope hot how hurt i'd i'll i'm i've ice if " +
        "in inside into is isn't it it's its just keep kept kid kids kill kind kiss knew know known last late later " +
        "lay learn leave left lie life light like line lips listen little live lonely long look looking lose losing " +
        "lost lot loud love loved lover low lucky lying mad made make makes making man many may maybe mean memories mind " +
        "mine miss moment money moon more morning most mother move much music must my myself name need needed never new " +
        "next nice night nights no nobody not nothing now of off oh okay old on once one only open or other our out " +
        "over own pain paint paradise part past people place play please power pretty pull push put rain ran reach " +
        "read ready real really reason red remember rest ride right ring rise road rock roll room run running sad said " +
        "same save saw say saying says scared sea see seem seen send set shake she she's shine short should shot show " +
        "side sight sign silence since sing single sister sit skin sky sleep slow small smile smoke so some somebody " +
        "someone something sometimes song soon sorry soul sound speak stand star stars start stay still stop story " +
        "strong such summer sun sure sweet take taken taking talk talking taste tear tears tell than thank that that's " +
        "the their them then there there's these they they're thing things think this those though thought through " +
        "throw tide till time times tired today together told tomorrow tonight too took touch town tried true truth try " +
        "trying turn two under until up us use very voice wait waiting wake walk wall wanna want wanted war was wasn't " +
        "watch water way we we'll we're we've wear went were what what's when where which while white who whole why " +
        "wide wild will win wind wish with without woman won't wonder word words work world worth would wouldn't wrong " +
        "yes yesterday yet you you'd you'll you're you've young your yours yourself ya'll y'all girl's boy's dreamin' " +
        "nothin' somethin' lovin' feelin' tryna kinda wanna gimme lemme outta ooh-la fallin' cryin' runnin' " +
        "forever shining glow golden rhythm beautiful heartbeat midnight sunrise sunset everybody's nobody's " +
        "mess messed baby's ocean river fly flying wings free shadows shadow ghost cool feet floor club party " +
        "loving wonderful perfect forget alive tonight's baby, bitch damn hey shit fuck fucking lord jesus"
        ).split(' ').map { it.trim(',', '.') }.filter { it.isNotEmpty() }.toSet()

    private val OTHER_WORDS: Set<String> = (
        // Hindi / Urdu / Punjabi (romanized)
        "hai hain hoon hun hu tha thi the nahi nahin nai na'i mein tu tum tera teri tere mera meri mere hamara " +
        "tumhara tumhari tumhare apna apni apne dil pyaar pyar ishq mohabbat ki ka ke ko se kya kyun kyon kaise " +
        "kab kahan yeh woh wo jo bhi aur sab koi kabhi jab tak raha rahi rahe kar karo karna karke mujhe tujhe " +
        "humko tumko sanam jaana jaan yaar yaara re ve sajna sajni saathi aankhen ankhiyan zindagi dard raat " +
        "baat baatein saath chand chaand duniya khuda rab dua sapna sapne naina nain mann mujhko tujhko lagta " +
        "lage laga lagi hota hoti hote gaya gayi gaye jaane jaana jaaun aaja aaya aayi aaye dekh dekho dekha " +
        "chal chalo chale chalna mil mile milna milke tujhse mujhse tumse humse unse usse kuch kuchh bina " +
        "bin har ek teen hum-tum tanha judaa juda yaadein yaad dhadkan dhadkane saanson " +
        "saansein rang rangi maahi mahi soniye sohni kudi munda mundeya jind jindri hoye balle oye sadda tenu " +
        "menu mainu tainu nu vich kithe kive hun haan nahin ab abhi phir fir zara thoda bahut bohot pe par " +
        "liye liya diya diye kaun tujh mujh hoke kehta kehti kehna sun suno suna bol bolo bole tujhe rehna " +
        "raho rahun jaise waise aisa aise kaisa wahi yahi vahi tab sabhi pehli pehla aankhon dilon " +
        // Spanish / Portuguese
        "que el los las un una uno yo tu te mi mis amor corazon corazón quiero eres como pero por para con es " +
        "mas más todo nada noche vida contigo besame bésame cuando donde dónde siempre nunca porque solo sola " +
        "tengo tienes estoy estas estás está esta este del al lo le se sin muy bien ya hoy aqui aquí ahora " +
        "mujer hombre baila bailar fuego cielo mundo despacito mami papi tú qué ti nos ella él vamos dime " +
        "eu você voce meu minha não nao sim saudade coração beijo amo tudo" +
        " " +
        // Korean / Japanese (romanized)
        "saranghae saranghae neoreul naega neo nan nae geu geudae ireoke uri hana dul oppa jagiya bogoshipda " +
        "watashi anata boku kimi ai suki daisuki kokoro sekai yume itsumo zutto naze ne yo wa ga wo dake"
        ).split(' ').map { it.trim() }.filter { it.isNotEmpty() }.toSet() - setOf("the", "do", "par", "pe")
}
