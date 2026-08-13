package com.assetsking.app.ui.screen

import com.assetsking.ledger.DebtStage
import com.assetsking.ledger.DebtTrend

internal fun v5StageLabel(stage: DebtStage): String = when (stage) {
    DebtStage.DEBT_FREE -> "已清零"
    DebtStage.CASH_SURVIVAL -> "现金流生存期"
    DebtStage.LIVE_TO_BONUS -> "活到年终奖"
    DebtStage.BONUS_PAYDOWN -> "年终奖集中降债"
    DebtStage.STOP_ROLLOVER -> "停止以贷养贷"
    DebtStage.STABLE_REDUCTION -> "稳定净降债"
}

internal fun v5TrendLabel(trend: DebtTrend): String = when (trend) {
    DebtTrend.REDUCING -> "正在降债"
    DebtTrend.FLAT -> "基本持平"
    DebtTrend.GROWING -> "负债仍在增加"
    DebtTrend.NO_ANCHOR -> "本月起建档"
}
