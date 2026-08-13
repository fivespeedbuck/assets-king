package com.assetsking.database

/**
 * 默认放行的「能扣钱 / 能到账」的 app 包名。
 *
 * 这只是**种子**，不是硬编码死的清单：监听服务会把每个推过通知的来源自动登记下来，
 * 设置页「通知来源」按登记结果列出开关。漏配的银行 app 只要推过一次通知就会出现在那里，
 * 用户一键打开即可，不必等改代码重新打包。
 */
val DEFAULT_PAYMENT_PACKAGES: Set<String> = setOf(
    // ── 三方支付 ──
    "com.tencent.mm",                        // 微信
    "com.eg.android.AlipayGphone",           // 支付宝
    "com.unionpay",                          // 云闪付
    // ── 银行 / 信用卡 ──
    "cmb.pb",                                // 招商银行
    "com.cmbchina.ccd.pluto.cmbActivity",    // 掌上生活（招行信用卡）
    "com.nbbank",                            // 宁波银行
    "com.cs_credit_bank",                    // 发现精彩（广发信用卡）— 真机实测包名
    "com.cgbchina.xpt",                      // 广发银行
    "com.gdb.client",                        // 广发银行（另一包名）
    "com.pingan.paces.ccms",                 // 平安信用卡 — 真机实测包名
    "com.icbc",                              // 工商银行
    "com.chinamworld.main",                  // 建设银行
    "com.android.bankabc",                   // 农业银行
    "com.chinamworld.bocmbci",               // 中国银行
    "com.bankcomm.Bankcomm",                 // 交通银行
    "cn.com.spdb.mobilebank.per",            // 浦发银行
    "com.spdbccc.app",                       // 浦大喜奔（浦发信用卡）
    "cn.com.cmbc.newmbank",                  // 民生银行
    "com.ecitic.bank.mobile",                // 中信银行
    "com.citiccard.mobilebank",              // 动卡空间（中信信用卡）
    "com.cib.cibmb",                         // 兴业银行
    "com.pingan.pabank.activity",            // 平安口袋银行
    "com.cebbank.mobile.cemb",               // 光大银行
    "com.yitong.mbank.psbc",                 // 邮储银行
    // ── 短信 ──
    // 银行的扣款短信是**每笔必到**的最可靠来源（宁波银行就只走短信不推 App），
    // 所以默认放行。短信箱噪音大得多（营销、账单、还款提醒），靠 NotificationParser
    // 的两层否决过滤，不靠白名单。ROM 之间包名不同，常见的都列上。
    "com.android.mms.service",               // 原生 / vivo
    "com.android.mms",
    "com.samsung.android.messaging",
    "com.miui.smsextra",
    "com.google.android.apps.messaging"
)
