package com.assetsking.ledger

/**
 * 初始分类库（REQ 初始分类库 §1-21 + docs/research/expense-category-taxonomy.md）。
 *
 * 一级 11 个，二级按用户真实生活场景（单身租房、无车、两猫三鸟、健身）。
 * defaultNecessary：true=必要，false=非必要，null=按场景（确认时预填 true 但允许一键切换）。
 */
data class CategorySeed(
    val id: String,                // 稳定 ID（预置用语义 slug；用户新增用 UUID）
    val name: String,              // 全名
    val shortName: String,         // 两字简称（宫格用，REQ 分类§26）
    val parentId: String?,         // null = 一级
    val iconKey: String,           // 线性图标库 key（UI 层映射到 material-icons-extended）
    val defaultNecessary: Boolean? = null,
    val kind: String = "EXPENSE"   // EXPENSE=消费分类，INCOME=收入分类（REQ 预期收入§4 独立小型分类库）
)

object DefaultCategories {

    val seeds: List<CategorySeed> = listOf(
        // ── 一级 ──
        CategorySeed("dining", "餐饮", "餐饮", null, "restaurant"),
        CategorySeed("housing", "居住生活", "居住", null, "home"),
        CategorySeed("transport", "出行", "出行", null, "directions-bus"),
        CategorySeed("pets", "宠物", "宠物", null, "pets"),
        CategorySeed("fitness", "健身运动", "健身", null, "fitness-center"),
        CategorySeed("shopping", "购物", "购物", null, "shopping-bag"),
        CategorySeed("medical", "医疗健康", "医疗", null, "local-hospital"),
        CategorySeed("personal-care", "个人护理", "护理", null, "spa"),
        CategorySeed("entertainment", "娱乐休闲", "娱乐", null, "sports-esports"),
        CategorySeed("learning", "学习成长", "学习", null, "school"),
        CategorySeed("social", "人情往来", "人情", null, "volunteer-activism"),

        // ── 餐饮（REQ 分类§12）──
        CategorySeed("dining-groceries", "买菜", "买菜", "dining", "shopping-cart", true),
        CategorySeed("dining-meal-prep", "健身餐", "轻食", "dining", "rice-bowl", true),
        CategorySeed("dining-dine-in", "堂食", "堂食", "dining", "restaurant-menu", true),
        CategorySeed("dining-takeout", "外卖", "外卖", "dining", "delivery-dining", true),
        CategorySeed("dining-snacks", "零食/嘴馋加餐", "零食", "dining", "cookie", false),
        CategorySeed("dining-treating", "请客", "请客", "dining", "group", false),
        CategorySeed("dining-other", "其他", "其他", "dining", "more-horiz", null),

        // ── 居住生活（REQ 分类§19）──
        CategorySeed("housing-rent", "房租", "房租", "housing", "apartment", true),
        CategorySeed("housing-hotel", "酒店", "酒店", "housing", "hotel", null),
        CategorySeed("housing-water", "水费", "水费", "housing", "water-drop", true),
        CategorySeed("housing-electricity", "电费", "电费", "housing", "bolt", true),
        CategorySeed("housing-gas", "燃气费", "燃气", "housing", "local-fire-department", true),
        CategorySeed("housing-phone", "手机话费", "话费", "housing", "smartphone", true),

        // ── 出行（REQ 分类§13）──
        CategorySeed("transport-bus", "公交", "公交", "transport", "directions-bus", true),
        CategorySeed("transport-ebike", "共享电瓶车", "电瓶", "transport", "electric-bike", true),
        CategorySeed("transport-metro", "地铁", "地铁", "transport", "subway", true),
        CategorySeed("transport-taxi", "打车", "打车", "transport", "local-taxi", false),
        CategorySeed("transport-rail", "高铁", "高铁", "transport", "train", false),
        CategorySeed("transport-flight", "飞机", "飞机", "transport", "flight", false),
        CategorySeed("transport-other", "其他", "其他", "transport", "more-horiz", null),

        // ── 宠物（REQ 分类§14）──
        CategorySeed("pets-cat-food", "猫粮", "猫粮", "pets", "pets", true),
        CategorySeed("pets-cat-litter", "猫砂", "猫砂", "pets", "sanitizer", true),
        CategorySeed("pets-cat-supplies", "猫用品", "猫用", "pets", "inventory-2", true),
        CategorySeed("pets-cat-medical", "猫医疗", "猫医", "pets", "medical-services", true),
        CategorySeed("pets-bird-food", "鸟粮", "鸟粮", "pets", "egg", true),
        CategorySeed("pets-bird-supplies", "鸟用品", "鸟用", "pets", "inventory-2", true),
        CategorySeed("pets-bird-medical", "鸟医疗", "鸟医", "pets", "medical-services", true),

        // ── 健身运动（REQ 分类§5-6，默认非必要）──
        CategorySeed("fitness-membership", "健身会员", "会员", "fitness", "card-membership", false),
        CategorySeed("fitness-classes", "运动课程", "课程", "fitness", "sports-gymnastics", false),
        CategorySeed("fitness-venue", "场地费用", "场地", "fitness", "stadium", false),
        CategorySeed("fitness-gear", "器材装备", "器材", "fitness", "fitness-center", false),
        CategorySeed("fitness-supplements", "运动补剂", "补剂", "fitness", "science", false),
        CategorySeed("fitness-other", "其他", "其他", "fitness", "more-horiz", false),

        // ── 购物（REQ 分类§15）──
        CategorySeed("shopping-daily", "日用品", "日用", "shopping", "cleaning-services", true),
        CategorySeed("shopping-clothes", "服饰鞋包", "服饰", "shopping", "checkroom", false),
        CategorySeed("shopping-electronics", "数码电器", "数码", "shopping", "devices", false),
        CategorySeed("shopping-home", "家居用品", "家居", "shopping", "chair", false),
        CategorySeed("shopping-beauty", "美妆护理", "美妆", "shopping", "face-retouching-natural", false),
        CategorySeed("shopping-hobbies", "兴趣爱好", "兴趣", "shopping", "interests", false),
        CategorySeed("shopping-gifts", "礼物", "礼物", "shopping", "card-giftcard", false),

        // ── 医疗健康（REQ 分类§16，全必要）──
        CategorySeed("medical-visit", "挂号就诊", "挂号", "medical", "assignment-ind", true),
        CategorySeed("medical-drugs", "药品", "药品", "medical", "medication", true),
        CategorySeed("medical-checkup", "体检", "体检", "medical", "fact-check", true),
        CategorySeed("medical-dental", "牙科", "牙科", "medical", "mood", true),
        CategorySeed("medical-rehab", "康复理疗", "理疗", "medical", "healing", true),

        // ── 个人护理（REQ 分类§16）──
        CategorySeed("personal-care-haircut", "理发", "理发", "personal-care", "content-cut", true),
        CategorySeed("personal-care-wash", "洗护服务", "洗护", "personal-care", "shower", true),
        CategorySeed("personal-care-nails", "美容美甲", "美甲", "personal-care", "spa", false),
        CategorySeed("personal-care-massage", "按摩放松", "按摩", "personal-care", "self-improvement", false),

        // ── 娱乐休闲（REQ 分类§18，全非必要）──
        CategorySeed("entertainment-movies", "电影演出", "电影", "entertainment", "movie", false),
        CategorySeed("entertainment-games", "游戏", "游戏", "entertainment", "sports-esports", false),
        CategorySeed("entertainment-ktv", "KTV", "K歌", "entertainment", "mic", false),
        CategorySeed("entertainment-boardgames", "桌游剧本杀", "剧本", "entertainment", "casino", false),
        CategorySeed("entertainment-online", "线上娱乐", "线上", "entertainment", "live-tv", false),

        // ── 学习成长（REQ 分类§18，全非必要）──
        CategorySeed("learning-books", "书籍资料", "书籍", "learning", "menu-book", false),
        CategorySeed("learning-courses", "课程培训", "培训", "learning", "cast-for-education", false),
        CategorySeed("learning-exams", "考试认证", "考试", "learning", "fact-check", false),
        CategorySeed("learning-stationery", "文具办公", "文具", "learning", "edit-note", false),

        // ── 人情往来（REQ 分类§18，全非必要）──
        CategorySeed("social-red-packet", "红包礼金", "红包", "social", "redeem", false),
        CategorySeed("social-family", "孝敬家人", "孝敬", "social", "family-restroom", false),
        CategorySeed("social-donation", "公益捐赠", "捐赠", "social", "volunteer-activism", false)
    )

    /** 收入分类库（REQ 预期收入§4）：独立于消费分类的小型分类库，5 个一级默认；编辑器内可新增一级/二级收入分类。 */
    val incomeSeeds: List<CategorySeed> = listOf(
        CategorySeed("income-salary", "工资", "工资", null, "payments", kind = "INCOME"),
        CategorySeed("income-bonus", "年终奖", "年终", null, "savings", kind = "INCOME"),
        CategorySeed("income-parttime", "兼职劳务", "兼职", null, "work", kind = "INCOME"),
        CategorySeed("income-interest", "利息收益", "利息", null, "percent", kind = "INCOME"),
        CategorySeed("income-gift", "红包赠与", "红包", null, "redeem", kind = "INCOME")
    )
}
