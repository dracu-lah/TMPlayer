package com.tmplayer.data

/**
 * File names in the shapes real chats post them, with made-up titles, and what each should read
 * as. Shared by the name, grouping and search tests, so a parser change is checked against one
 * list. Every title here is invented; none is a real film or show.
 */
object NameCorpus {

    /** One name and its reading. Null fields are not checked. */
    data class Case(
        val fileName: String,
        val title: String,
        val year: Int? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val part: Int? = null,
        val extra: ExtraKind? = null,
        val airDate: String? = null,
        val caption: String? = null,
    )

    val CASES = listOf(
        // A movie channel's handle, in every way the shapes seen in real chats stapled it on (HX31).
        Case("@CC_Silver_Harbour_Night.mp4", "Silver Harbour Night"),
        Case("_@CC_Silver_Harbour_Night_2.mkv", "Silver Harbour Night 2"),
        Case("@CC_ALL_Copper_Lantern_Road.mkv", "Copper Lantern Road"),
        Case("@CC_MAL_Copper_Lantern_Road.mkv", "Copper Lantern Road"),
        Case("@cinema_company_The_Amber_Gate.mkv", "The Amber Gate"),
        Case("@Mallu_Movies_Reed_Harbour.mkv", "Reed Harbour"),
        Case("@WMR_Silver_Harbour.1990.Malayalam.720p.WebHD.x264.mp4", "Silver Harbour", 1990),
        Case("@CC Amber Gate 1978 Malayalam.mkv", "Amber Gate", 1978),
        Case("@CC Amber Gate.mkv", "Amber Gate"),
        Case("@CC.Silver.Harbour.1965.Malayalam @cinema_company.mp4", "Silver Harbour", 1965),
        Case("Silver Harbour 1972 WEBRip @cinema_company.mp4", "Silver Harbour", 1972),
        // A year run into its quality, a short language word and a tag character on the end.
        Case("Amber Gate 20241080p.mkv", "Amber Gate", 2024),
        Case("Amber Gate 2024720p WEB-DL.mkv", "Amber Gate", 2024),
        Case("Moon Ledger Mal.mkv", "Moon Ledger"),
        Case("Moon Ledger #Hin.mkv", "Moon Ledger"),
        Case("Moon Ledger Tam 2023 720p.mkv", "Moon Ledger", 2023),
        Case("Moon Ledger (2k23) Mal.mkv", "Moon Ledger (2k23)"),
        Case("Moon Ledger - Eng.mkv", "Moon Ledger"),
        // Invisible characters and a leading tilde.
        Case("\u200B\uFE0FCopper Tide 1000.mp4", "Copper Tide 1000"),
        Case("~Reed Lantern (2023).mkv", "Reed Lantern", 2023),
        // A joiner after a virama spells a Malayalam letter, and stays.
        Case("അവൻ്‍ (2020).mkv", "അവൻ്‍", 2020),
        // Titles that must survive the rules above.
        Case("CC.mkv", "CC"),
        Case("Mal (2011) 720p.mkv", "Mal", 2011),
        Case("Tales of Tam.mkv", "Tales of Tam"),
        Case("Hindi Medium Lite (2017).mkv", "Hindi Medium Lite", 2017),
        // A channel handle run into the name with underscores.
        Case("@HarbourMob_LinkZz_Night_Lantern_2022_Tamil_Proper_HQ_HDRip_700MB_x264_AAC.mkv", "Night Lantern", 2022),
        Case("@HEVC_Mob_Night_Lantern_2022_Tamil_720p.mkv", "Night Lantern", 2022),
        Case("@TG_Clips4u -Night Lantern (2024) Tamil 720p.mkv", "Night Lantern", 2024),
        Case("Paper_Kite_2005_Tamil_DVDRip_XviD_2.avi", "Paper Kite", 2005),
        Case("Paper Kite (2009) Tamil HDRip 720p x264.mkv", "Paper Kite", 2009),
        Case("www.filmsite.xyz - Paper Kite (2024) 1080p WEB-DL.mkv", "Paper Kite", 2024),
        Case("Paper Kite Tamil Hd.mp4", "Paper Kite"),
        Case("Paper Kite Full Movie HD | Arun Devan.mp4", "Paper Kite"),
        Case("Velvet Hour [ Arun Devan, Mira Joshi ].mp4", "Velvet Hour"),
        // Numbers that are not episodes.
        Case("Siren Patrol 100.mp4", "Siren Patrol 100"),
        Case("Harbour Lights Part 2 (2019) 720p.mkv", "Harbour Lights Part 2", 2019),
        Case("Harbour Lights Chapter 3 (2021) 1080p.mkv", "Harbour Lights Chapter 3", 2021),
        Case("Coast Run 2 2014 720p HDRip.mkv", "Coast Run 2", 2014),
        Case("1944.1080p.BluRay.mkv", "1944"),
        Case("1944 (2015) 1080p.mkv", "1944", 2015),
        Case("Tide 1080.mkv", "Tide 1080"),
        // Tag words inside a title, before its year.
        Case("The Italian Garden 2003 1080p BluRay.mkv", "The Italian Garden", 2003),
        Case("A Complete Stranger (2024) 720p.mkv", "A Complete Stranger", 2024),
        Case("The Final Cut 2004 720p.mkv", "The Final Cut", 2004),
        Case("Into the Web 2020 HDRip.mkv", "Into the Web", 2020),
        Case("Harbour Notes Tamil 2022 HDRip.mkv", "Harbour Notes", 2022),
        Case("Learn_Tamil_Fast.mp4", "Learn Tamil Fast"),
        // Split films.
        Case("Coast Run 2014 720p CD1.avi", "Coast Run", 2014, part = 1),
        Case("Coast.Run.2014.720p.CD2.avi", "Coast Run", 2014, part = 2),
        Case("Coast.Run.2014.Part2.mkv", "Coast Run", 2014, part = 2),
        Case("Coast Run Pt1.mkv", "Coast Run", part = 1),
        // Extras.
        Case("Coast Run (2014) Official Trailer.mp4", "Coast Run", 2014, extra = ExtraKind.Trailer),
        Case("Trailer - Coast Run (2014).mp4", "Coast Run", 2014, extra = ExtraKind.Trailer),
        Case("Coast Run 2014 Teaser 2 1080p.mp4", "Coast Run", 2014, extra = ExtraKind.Teaser),
        Case("The Sample Room (2020) 720p.mkv", "The Sample Room", 2020),
        Case("Sample Room Tales.mkv", "Sample Room Tales"),
        // Episodes, and episodes by air date.
        Case("Harbour.Notes.S01E02.1080p.WEB-DL.mkv", "Harbour Notes", season = 1, episode = 2),
        Case("Morning Desk 2024.10.14 720p.mp4", "Morning Desk", 2024, airDate = "2024-10-14"),
        Case("Morning Desk 14-10-2024.mp4", "Morning Desk", 2024, airDate = "2024-10-14"),
        // Other scripts and decorations.
        Case("இரவு நிலா (2023) 720p.mkv", "இரவு நிலா", 2023),
        Case("ليلة القمر (2021) 1080p.mkv", "ليلة القمر", 2021),
        Case("🎬🔥 Night Lantern (2022) 🔥.mkv", "Night Lantern", 2022),
    )
}
