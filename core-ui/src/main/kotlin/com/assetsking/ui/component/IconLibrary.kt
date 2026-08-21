package com.assetsking.ui.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apartment
import androidx.compose.material.icons.filled.AssignmentInd
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.BabyChangingStation
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BusinessCenter
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.CardMembership
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.CastForEducation
import androidx.compose.material.icons.filled.Chair
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Cookie
import androidx.compose.material.icons.filled.DeliveryDining
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Egg
import androidx.compose.material.icons.filled.ElectricBike
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FaceRetouchingNatural
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.FamilyRestroom
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Hotel
import androidx.compose.material.icons.filled.Interests
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalGroceryStore
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.LocalPharmacy
import androidx.compose.material.icons.filled.LocalTaxi
import androidx.compose.material.icons.filled.Luggage
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Paid
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Redeem
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material.icons.filled.RiceBowl
import androidx.compose.material.icons.filled.Sanitizer
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Shower
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.SportsGymnastics
import androidx.compose.material.icons.filled.Stadium
import androidx.compose.material.icons.filled.Subway
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material.icons.filled.VolunteerActivism
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Work
import androidx.compose.material.icons.filled.RequestQuote
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
        Entry("local-grocery-store", "买菜 超市 菜市场 生鲜 采购", Icons.Filled.LocalGroceryStore),
        Entry("local-cafe", "咖啡 咖啡店 茶饮 饮品 下午茶", Icons.Filled.LocalCafe),
        Entry("rice-bowl", "轻食 健身餐 饭", Icons.Filled.RiceBowl),
        Entry("restaurant-menu", "堂食 菜单 点餐", Icons.Filled.RestaurantMenu),
        Entry("delivery-dining", "外卖 配送", Icons.Filled.DeliveryDining),
        Entry("cookie", "零食 甜品 嘴馋", Icons.Filled.Cookie),
        Entry("group", "请客 聚餐 朋友", Icons.Filled.Group),
        Entry("apartment", "房租 公寓", Icons.Filled.Apartment),
        Entry("hotel", "酒店 住宿", Icons.Filled.Hotel),
        Entry("water-drop", "水费 水 水电 水务 公用事业", Icons.Filled.WaterDrop),
        Entry("bolt", "电费 电 水电 电力 公用事业", Icons.Filled.Bolt),
        Entry("local-fire-department", "燃气 煤气 火", Icons.Filled.LocalFireDepartment),
        Entry("smartphone", "话费 手机 通信", Icons.Filled.Smartphone),
        Entry("electric-bike", "电瓶车 共享车", Icons.Filled.ElectricBike),
        Entry("directions-car", "汽车 自驾 交通 车辆", Icons.Filled.DirectionsCar),
        Entry("two-wheeler", "摩托车 电动车 骑行 交通", Icons.Filled.TwoWheeler),
        Entry("local-parking", "停车 停车费 汽车", Icons.Filled.LocalParking),
        Entry("local-gas-station", "加油 汽油 汽车 车费", Icons.Filled.LocalGasStation),
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
        Entry("laptop", "电脑 笔记本 数码 办公", Icons.Filled.Laptop),
        Entry("chair", "家居 家具", Icons.Filled.Chair),
        Entry("face-retouching-natural", "美妆 美容", Icons.Filled.FaceRetouchingNatural),
        Entry("interests", "兴趣 爱好", Icons.Filled.Interests),
        Entry("card-giftcard", "礼物 礼品卡", Icons.Filled.CardGiftcard),
        Entry("assignment-ind", "挂号 就诊", Icons.Filled.AssignmentInd),
        Entry("medication", "药品 药", Icons.Filled.Medication),
        Entry("local-pharmacy", "药房 药店 药品 医疗", Icons.Filled.LocalPharmacy),
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
        Entry("business-center", "办公 工作 职场 商务", Icons.Filled.BusinessCenter),
        Entry("child-care", "育儿 婴儿 儿童 子女", Icons.Filled.ChildCare),
        Entry("baby-changing-station", "育儿 婴儿 母婴 尿布", Icons.Filled.BabyChangingStation),
        Entry("luggage", "旅行 旅游 行李 出游", Icons.Filled.Luggage),
        Entry("explore", "旅行 旅游 景点 探索", Icons.Filled.Explore),
        Entry("redeem", "红包 礼金", Icons.Filled.Redeem),
        Entry("family-restroom", "家人 家庭", Icons.Filled.FamilyRestroom),
        Entry("payments", "工资 薪资 薪水 月薪 发薪 收入", Icons.Filled.Payments),
        Entry("savings", "储蓄 存钱 年终奖 奖金 备用金", Icons.Filled.Savings),
        Entry("work", "兼职 劳务 工作", Icons.Filled.Work),
        Entry("percent", "利息 收益 理财", Icons.Filled.Percent),
        Entry("account-balance-wallet", "资产 账户 钱包 余额 资金", Icons.Filled.AccountBalanceWallet),
        Entry("account-balance", "税费 税务 缴税 账户 银行", Icons.Filled.AccountBalance),
        Entry("policy", "保险 保单 商业保险 保障", Icons.Filled.Policy),
        Entry("health-and-safety", "保险 医保 社保 安全 保障", Icons.Filled.HealthAndSafety),
        Entry("request-quote", "税费 税务 发票 报价", Icons.Filled.RequestQuote),
        Entry("receipt-long", "税费 账单 发票 收据 费用", Icons.Filled.ReceiptLong),
        Entry("paid", "缴费 费用 付款 已付", Icons.Filled.Paid),
        Entry("trending-up", "投资 理财 增值 上涨", Icons.Filled.TrendingUp),
        Entry("show-chart", "投资 股票 基金 行情 收益", Icons.Filled.ShowChart),
        Entry("more-horiz", "其他 更多", Icons.Filled.MoreHoriz),
    )

    fun byKey(key: String): ImageVector = entries.firstOrNull { it.key == key }?.icon ?: Icons.Filled.MoreHoriz

    /** 中文关键词搜索（REQ 编辑器§7）。 */
    fun search(query: String): List<Entry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return entries
        return entries.filter { it.keywords.lowercase().contains(q) || it.key.lowercase().contains(q) }
    }
}
