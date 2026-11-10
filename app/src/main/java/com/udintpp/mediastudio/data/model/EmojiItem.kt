package com.udintpp.mediastudio.data.model

data class EmojiItem(
    val unicode: String,
    val name: String,
    val keywords: List<String>,
    val category: EmojiCategory
)

enum class EmojiCategory(val label: String) {
    ALL("Semua"),
    SMILEYS("Ekspresi"),
    GESTURES("Tangan & Gestur"),
    LOVE("Cinta & Hati"),
    ANIMALS("Hewan & Alam"),
    FOOD("Makanan & Minuman"),
    FIRE_EFFECTS("Efek & Spark"),
    OBJECTS("Objek & Pesta")
}

object EmojiCatalog {
    val items: List<EmojiItem> = listOf(
        // Smileys & Ekspresi
        EmojiItem("😀", "Senyum Lebar", listOf("senyum", "smile", "happy", "senang", "gembira"), EmojiCategory.SMILEYS),
        EmojiItem("😃", "Senyum Ceria", listOf("senyum", "smile", "ceria", "happy"), EmojiCategory.SMILEYS),
        EmojiItem("😄", "Tertawa Lebar", listOf("laugh", "ketawa", "senang", "happy"), EmojiCategory.SMILEYS),
        EmojiItem("😁", "Menyeringai", listOf("grin", "gigi", "senyum", "smile"), EmojiCategory.SMILEYS),
        EmojiItem("😆", "Tertawa Terbahak", listOf("laughing", "ngakak", "lucu", "wkwk"), EmojiCategory.SMILEYS),
        EmojiItem("😂", "Tertawa Menangis", listOf("joy", "ngakak", "lucu", "rofl", "lol"), EmojiCategory.SMILEYS),
        EmojiItem("🤣", "Guling-guling Tertawa", listOf("rofl", "ngakak parah", "lucu banget"), EmojiCategory.SMILEYS),
        EmojiItem("😊", "Senyum Malu", listOf("blush", "manis", "senyum", "sweet"), EmojiCategory.SMILEYS),
        EmojiItem("😇", "Malaikat Suci", listOf("angel", "baik", "halo", "polos"), EmojiCategory.SMILEYS),
        EmojiItem("🙂", "Senyum Tipis", listOf("smile", "santai", "oke"), EmojiCategory.SMILEYS),
        EmojiItem("😉", "Kedip Mata", listOf("wink", "mengedipkan", "genit", "joke"), EmojiCategory.SMILEYS),
        EmojiItem("😍", "Mata Hati Cinta", listOf("love", "cinta", "suka", "heart eyes", "naksir"), EmojiCategory.LOVE),
        EmojiItem("🥰", "Penuh Cinta", listOf("love", "sayang", "cinta", "blessed"), EmojiCategory.LOVE),
        EmojiItem("😘", "Kecup Cium", listOf("kiss", "cium", "sayang", "love"), EmojiCategory.LOVE),
        EmojiItem("😎", "Keren Kacamata", listOf("cool", "keren", "gaya", "sunglasses"), EmojiCategory.SMILEYS),
        EmojiItem("🤩", "Mata Bintang Terpukau", listOf("star", "kagum", "keren", "wow", "excited"), EmojiCategory.SMILEYS),
        EmojiItem("🥳", "Pesta Terompet", listOf("party", "pesta", "selamat", "celebrate"), EmojiCategory.OBJECTS),
        EmojiItem("😏", "Senyum Sinis", listOf("smirk", "curiga", "sarkas"), EmojiCategory.SMILEYS),
        EmojiItem("🤔", "Berpikir", listOf("think", "mikir", "bingung", "ponder"), EmojiCategory.SMILEYS),
        EmojiItem("🤫", "Diam Rahasia", listOf("shh", "quiet", "rahasia", "silent"), EmojiCategory.SMILEYS),
        EmojiItem("😱", "Terkejut Teriak", listOf("scream", "kaget", "shock", "scared"), EmojiCategory.SMILEYS),
        EmojiItem("🥺", "Memohon Haru", listOf("pleading", "sedih", "minta", "terharu"), EmojiCategory.SMILEYS),
        EmojiItem("😭", "Menangis Banjir", listOf("cry", "nangis", "sedih", "sad", "air mata"), EmojiCategory.SMILEYS),
        EmojiItem("😡", "Marah Merah", listOf("angry", "marah", "kesal", "mad"), EmojiCategory.SMILEYS),
        EmojiItem("🤯", "Kepala Meledak", listOf("mind blown", "meledak", "kaget banget", "wow"), EmojiCategory.SMILEYS),
        EmojiItem("😴", "Tidur Pulas", listOf("sleep", "ngantuk", "tidur", "tired"), EmojiCategory.SMILEYS),

        // Gestures
        EmojiItem("👍", "Jempol Mantap", listOf("thumbs up", "oke", "mantap", "setuju", "good"), EmojiCategory.GESTURES),
        EmojiItem("👎", "Jempol Kurang", listOf("thumbs down", "jelek", "tidak setuju", "bad"), EmojiCategory.GESTURES),
        EmojiItem("👌", "Tangan Sempurna OK", listOf("ok", "oke", "sip", "mantap"), EmojiCategory.GESTURES),
        EmojiItem("✌️", "Tangan Damai Peace", listOf("peace", "dua", "damai", "victory"), EmojiCategory.GESTURES),
        EmojiItem("🤞", "Semoga Beruntung", listOf("fingers crossed", "semoga", "beruntung"), EmojiCategory.GESTURES),
        EmojiItem("🤟", "Love You Tangan", listOf("love you", "metal", "rock"), EmojiCategory.GESTURES),
        EmojiItem("🤘", "Salam Metal Rock", listOf("rock", "metal", "musik", "keren"), EmojiCategory.GESTURES),
        EmojiItem("👏", "Tepuk Tangan", listOf("applause", "tepuk", "selamat", "salut"), EmojiCategory.GESTURES),
        EmojiItem("🙌", "Angkat Tangan", listOf("celebrate", "hore", "syukur"), EmojiCategory.GESTURES),
        EmojiItem("🙏", "Doa Maaf", listOf("pray", "terima kasih", "maaf", "syukur", "tolong"), EmojiCategory.GESTURES),
        EmojiItem("💪", "Kekuatan Otot", listOf("strong", "semangat", "kuat", "muscle"), EmojiCategory.GESTURES),
        EmojiItem("🤝", "Jabat Tangan", listOf("deal", "sepakat", "salam", "setuju"), EmojiCategory.GESTURES),

        // Love & Hearts
        EmojiItem("❤️", "Hati Merah", listOf("heart", "love", "cinta", "hati", "sayang"), EmojiCategory.LOVE),
        EmojiItem("💖", "Hati Berkilau", listOf("sparkle heart", "cinta", "sayang", "glow"), EmojiCategory.LOVE),
        EmojiItem("💙", "Hati Biru", listOf("blue heart", "cinta", "damai"), EmojiCategory.LOVE),
        EmojiItem("💜", "Hati Ungu", listOf("purple heart", "bts", "ungu"), EmojiCategory.LOVE),
        EmojiItem("🖤", "Hati Hitam", listOf("black heart", "gelap", "gothic"), EmojiCategory.LOVE),
        EmojiItem("💔", "Patah Hati", listOf("broken heart", "patah hati", "putus", "galau"), EmojiCategory.LOVE),
        EmojiItem("💘", "Panah Asmara Cupid", listOf("cupid", "panah", "jatuh cinta"), EmojiCategory.LOVE),

        // Effects & Fire
        EmojiItem("🔥", "Api Membara Hot", listOf("fire", "api", "hot", "panas", "keren", "viral"), EmojiCategory.FIRE_EFFECTS),
        EmojiItem("✨", "Kilau Sparkles", listOf("sparkle", "bintang", "bersih", "cantik", "glowing"), EmojiCategory.FIRE_EFFECTS),
        EmojiItem("💥", "Ledakan Boom", listOf("boom", "ledakan", "meledak", "pop"), EmojiCategory.FIRE_EFFECTS),
        EmojiItem("⚡", "Petir Kilat", listOf("lightning", "petir", "kilat", "cepat", "listrik"), EmojiCategory.FIRE_EFFECTS),
        EmojiItem("🎉", "Popper Pesta Confetti", listOf("tada", "pesta", "selamat", "congrats"), EmojiCategory.OBJECTS),
        EmojiItem("🎊", "Bola Konfeti", listOf("confetti", "perayaan", "selamat"), EmojiCategory.OBJECTS),
        EmojiItem("👑", "Mahkota Raja", listOf("crown", "raja", "ratu", "juara", "king", "queen"), EmojiCategory.OBJECTS),
        EmojiItem("⭐", "Bintang Emas", listOf("star", "bintang", "terbaik", "favorit"), EmojiCategory.OBJECTS),
        EmojiItem("💯", "Nilai 100 Sempurna", listOf("100", "seratus", "sempurna", "perfect"), EmojiCategory.OBJECTS),

        // Animals
        EmojiItem("🐱", "Kucing Gemas", listOf("cat", "kucing", "lucu", "meow"), EmojiCategory.ANIMALS),
        EmojiItem("🐶", "Anjing Setia", listOf("dog", "anjing", "puppy", "guguk"), EmojiCategory.ANIMALS),
        EmojiItem("🦁", "Singa Perkasa", listOf("lion", "singa", "raja"), EmojiCategory.ANIMALS),
        EmojiItem("🐼", "Panda Lucu", listOf("panda", "lucu", "gemas"), EmojiCategory.ANIMALS),
        EmojiItem("🦋", "Kupu-kupu Indah", listOf("butterfly", "kupu", "cantik", "alam"), EmojiCategory.ANIMALS),
        EmojiItem("🌹", "Mawar Merah", listOf("rose", "bunga", "mawar", "romantis"), EmojiCategory.ANIMALS),

        // Food & Drink
        EmojiItem("☕", "Kopi Hangat", listOf("coffee", "kopi", "santai", "ngopi"), EmojiCategory.FOOD),
        EmojiItem("🍕", "Pizza Lezat", listOf("pizza", "makanan", "enak"), EmojiCategory.FOOD),
        EmojiItem("🍔", "Burger Nikmat", listOf("burger", "makanan", "fastfood"), EmojiCategory.FOOD),
        EmojiItem("🍦", "Es Krim Manis", listOf("ice cream", "eskrim", "dingin", "manis"), EmojiCategory.FOOD),
        EmojiItem("🍿", "Popcorn Nonton", listOf("popcorn", "bioskop", "film", "snack"), EmojiCategory.FOOD)
    )

    fun search(query: String, category: EmojiCategory = EmojiCategory.ALL): List<EmojiItem> {
        val q = query.trim().lowercase()
        return items.filter { item ->
            val matchCategory = (category == EmojiCategory.ALL || item.category == category)
            if (!matchCategory) return@filter false
            if (q.isEmpty()) return@filter true
            item.name.lowercase().contains(q) ||
                    item.keywords.any { it.lowercase().contains(q) } ||
                    item.unicode.contains(q)
        }
    }
}
