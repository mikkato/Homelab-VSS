package ru.mytolm.frgate.util

/** Русские подписи severity и меток объектов (сервер отдаёт COCO-английский). */
object LabelsRu {
    private val severity = mapOf(
        "alert" to "Тревога",
        "detection" to "Детекция",
        "motion" to "Движение"
    )

    private val labels = mapOf(
        "person" to "человек",
        "animal" to "животное",
        "car" to "машина",
        "truck" to "грузовик",
        "bus" to "автобус",
        "motorcycle" to "мотоцикл",
        "bicycle" to "велосипед",
        "train" to "поезд",
        "dog" to "собака",
        "cat" to "кошка",
        "bird" to "птица",
        "horse" to "лошадь",
        "cow" to "корова",
        "sheep" to "овца",
        "bear" to "медведь",
        "elephant" to "слон",
        "backpack" to "рюкзак",
        "umbrella" to "зонт",
        "handbag" to "сумка",
        "suitcase" to "чемодан",
        "frisbee" to "фрисби",
        "skis" to "лыжи",
        "snowboard" to "сноуборд",
        "ball" to "мяч",
        "kite" to "воздушный змей",
        "bat" to "бита",
        "glove" to "перчатка",
        "skateboard" to "скейтборд",
        "surfboard" to "доска",
        "tennis racket" to "ракетка",
        "bottle" to "бутылка",
        "bench" to "скамейка",
        "plant" to "растение",
        "tv" to "телевизор",
        "laptop" to "ноутбук",
        "mouse" to "мышь",
        "remote" to "пульт",
        "keyboard" to "клавиатура",
        "cell phone" to "телефон",
        "book" to "книга",
        "clock" to "часы",
        "vase" to "ваза",
        "teddy-bear" to "мишка",
        "deer" to "олень",
        "fox" to "лиса",
        "raccoon" to "енот",
        "cat-family" to "кошачьи"
    )

    fun severityRu(s: String): String = severity[s] ?: s
    fun labelRu(l: String): String = labels[l.lowercase()] ?: l
    fun labelsRu(list: Collection<String>): String = list.joinToString(", ") { labelRu(it) }
}
