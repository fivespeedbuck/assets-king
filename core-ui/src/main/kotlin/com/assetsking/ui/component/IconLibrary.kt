package com.assetsking.ui.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apartment
import androidx.compose.material.icons.filled.AssignmentInd
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.CardMembership
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.CastForEducation
import androidx.compose.material.icons.filled.Chair
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Cookie
import androidx.compose.material.icons.filled.DeliveryDining
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Egg
import androidx.compose.material.icons.filled.ElectricBike
import androidx.compose.material.icons.filled.FaceRetouchingNatural
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.FamilyRestroom
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Hotel
import androidx.compose.material.icons.filled.Interests
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.LocalTaxi
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Redeem
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material.icons.filled.RiceBowl
import androidx.compose.material.icons.filled.Sanitizer
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Shower
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.SportsGymnastics
import androidx.compose.material.icons.filled.Stadium
import androidx.compose.material.icons.filled.Subway
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.VolunteerActivism
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 分类图标库（REQ 编辑器§7）：统一线性风格、离线、中文关键词搜索。
 * key 与 DefaultCategories 种子的 iconKey 对应；用户新增分类也从这里选。
 */
object IconLibrary {
    data class Entry(val key: String, val keywords: String, val icon: ImageVector)

    val entries: List<Entry> = listOf(
        Entry("restaurant", "餐饮 吃饭 饭店", Icons.Filled.Restaurant),
        Entry("home", "居住 房子 家", Icons.Filled.Home),
        Entry("directions-bus", "出行 公交 交通", Icons.Filled.DirectionsBus),
        Entry("pets", "宠物 猫 狗 动物", Icons.Filled.Pets),
        Entry("fitness-center", "健身 运动 锻炼", Icons.Filled.FitnessCenter),
        Entry("shopping-bag", "购物 商店 买", Icons.Filled.ShoppingBag),
        Entry("local-hospital", "医疗 医院 健康", Icons.Filled.LocalHospital),
        Entry("spa", "护理 美容 放松", Icons.Filled.Spa),
        Entry("sports-esports", "娱乐 游戏 电竞", Icons.Filled.SportsEsports),
        Entry("school", "学习 学校 教育", Icons.Filled.School),
        Entry("volunteer-activism", "人情 往来 公益", Icons.Filled.VolunteerActivism),
        Entry("shopping-cart", "买菜 购物车 采购", Icons.Filled.ShoppingCart),
        Entry("rice-bowl", "轻食 健身餐 饭", Icons.Filled.RiceBowl),
        Entry("restaurant-menu", "堂食 菜单 点餐", Icons.Filled.RestaurantMenu),
        Entry("delivery-dining", "外卖 配送", Icons.Filled.DeliveryDining),
        Entry("cookie", "零食 甜品 嘴馋", Icons.Filled.Cookie),
        Entry("group", "请客 聚餐 朋友", Icons.Filled.Group),
        Entry("apartment", "房租 公寓", Icons.Filled.Apartment),
        Entry("hotel", "酒店 住宿", Icons.Filled.Hotel),
        Entry("water-drop", "水费 水", Icons.Filled.WaterDrop),
        Entry("bolt", "电费 电", Icons.Filled.Bolt),
        Entry("local-fire-department", "燃气 煤气 火", Icons.Filled.LocalFireDepartment),
        Entry("smartphone", "话费 手机 通信", Icons.Filled.Smartphone),
        Entry("electric-bike", "电瓶车 共享车", Icons.Filled.ElectricBike),
        Entry("subway", "地铁", Icons.Filled.Subway),
        Entry("local-taxi", "打车 出租车", Icons.Filled.LocalTaxi),
        Entry("train", "高铁 火车", Icons.Filled.Train),
        Entry("flight", "飞机 航班", Icons.Filled.Flight),
        Entry("sanitizer", "猫砂 清洁", Icons.Filled.Sanitizer),
        Entry("inventory-2", "用品 装备 库存", Icons.Filled.Inventory2),
        Entry("medical-services", "医疗 诊疗", Icons.Filled.MedicalServices),
        Entry("egg", "鸟粮 鸟 蛋", Icons.Filled.Egg),
        Entry("card-membership", "会员 卡", Icons.Filled.CardMembership),
        Entry("sports-gymnastics", "课程 体操 训练", Icons.Filled.SportsGymnastics),
        Entry("stadium", "场地 场馆", Icons.Filled.Stadium),
        Entry("science", "补剂 营养 科学", Icons.Filled.Science),
        Entry("cleaning-services", "日用 清洁", Icons.Filled.CleaningServices),
        Entry("checkroom", "服饰 衣服", Icons.Filled.Checkroom),
        Entry("devices", "数码 电子 设备", Icons.Filled.Devices),
        Entry("chair", "家居 家具", Icons.Filled.Chair),
        Entry("face-retouching-natural", "美妆 美容", Icons.Filled.FaceRetouchingNatural),
        Entry("interests", "兴趣 爱好", Icons.Filled.Interests),
        Entry("card-giftcard", "礼物 礼品卡", Icons.Filled.CardGiftcard),
        Entry("assignment-ind", "挂号 就诊", Icons.Filled.AssignmentInd),
        Entry("medication", "药品 药", Icons.Filled.Medication),
        Entry("fact-check", "体检 检查 考试", Icons.Filled.FactCheck),
        Entry("mood", "牙科 牙齿", Icons.Filled.Mood),
        Entry("healing", "理疗 康复", Icons.Filled.Healing),
        Entry("content-cut", "理发 剪", Icons.Filled.ContentCut),
        Entry("shower", "洗护 洗浴", Icons.Filled.Shower),
        Entry("self-improvement", "按摩 放松", Icons.Filled.SelfImprovement),
        Entry("movie", "电影 影视", Icons.Filled.Movie),
        Entry("mic", "K歌 唱歌 麦", Icons.Filled.Mic),
        Entry("casino", "剧本杀 桌游", Icons.Filled.Casino),
        Entry("live-tv", "线上 直播 视频", Icons.Filled.LiveTv),
        Entry("menu-book", "书籍 阅读", Icons.Filled.MenuBook),
        Entry("cast-for-education", "培训 课程 教学", Icons.Filled.CastForEducation),
        Entry("edit-note", "文具 笔记 办公", Icons.Filled.EditNote),
        Entry("redeem", "红包 礼金", Icons.Filled.Redeem),
        Entry("family-restroom", "家人 家庭", Icons.Filled.FamilyRestroom),
        Entry("more-horiz", "其他 更多", Icons.Filled.MoreHoriz),
    )

    fun byKey(key: String): ImageVector = entries.firstOrNull { it.key == key }?.icon ?: Icons.Filled.MoreHoriz

    /** 中文关键词搜索（REQ 编辑器§7）。 */
    fun search(query: String): List<Entry> {
        val q = query.trim()
        if (q.isEmpty()) return entries
        return entries.filter { it.keywords.contains(q) || it.key.contains(q) }
    }
}
