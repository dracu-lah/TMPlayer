package com.tmplayer.i18n

/**
 * ISO 639-2 codes to their two letter ISO 639-1 form, for the platforms with no ISO 639-2 table of
 * their own: the browser and iOS (Foundation names languages but does not map 639-2 to 639-1). These
 * are the codes Matroska and MP4 tracks carry in practice, in the terminology form; the
 * bibliographic ones are in TrackCodes. The JDK derives the full table itself.
 */
internal val threeLetterLanguageCodes: Map<String, String> = mapOf(
    "eng" to "en", "spa" to "es", "fra" to "fr", "deu" to "de", "ita" to "it", "por" to "pt",
    "rus" to "ru", "ukr" to "uk", "pol" to "pl", "ces" to "cs", "slk" to "sk", "hun" to "hu",
    "ron" to "ro", "bul" to "bg", "srp" to "sr", "hrv" to "hr", "bos" to "bs", "slv" to "sl",
    "ell" to "el", "tur" to "tr", "ara" to "ar", "heb" to "he", "fas" to "fa", "urd" to "ur",
    "hin" to "hi", "ben" to "bn", "tam" to "ta", "tel" to "te", "mal" to "ml", "kan" to "kn",
    "mar" to "mr", "guj" to "gu", "pan" to "pa", "sin" to "si", "nep" to "ne", "tha" to "th",
    "vie" to "vi", "ind" to "id", "msa" to "ms", "fil" to "fil", "tgl" to "tl", "zho" to "zh",
    "jpn" to "ja", "kor" to "ko", "nld" to "nl", "swe" to "sv", "nor" to "no", "nob" to "nb",
    "nno" to "nn", "dan" to "da", "fin" to "fi", "est" to "et", "lav" to "lv", "lit" to "lt",
    "isl" to "is", "gle" to "ga", "cym" to "cy", "eus" to "eu", "cat" to "ca", "glg" to "gl",
    "kat" to "ka", "hye" to "hy", "aze" to "az", "kaz" to "kk", "uzb" to "uz", "mon" to "mn",
    "khm" to "km", "lao" to "lo", "mya" to "my", "amh" to "am", "swa" to "sw", "afr" to "af",
    "sqi" to "sq", "mkd" to "mk", "bel" to "be", "tat" to "tt", "kur" to "ku", "pus" to "ps",
    "som" to "so", "yor" to "yo", "zul" to "zu", "xho" to "xh", "lat" to "la",
)
