package com.jelly.fudai

enum class FsmState(@JvmField val label: String) {
    INIT("系统初始化"), PAGE_CHECK_NAV("页面识别与导航"),
    SCAN_FUDAI_ICON("福袋图标扫描"), OPEN_FUDAI_DETAIL("打开福袋详情"),
    CHECK_FUDAI_INFO("详情规则过滤"), ATTEND_CHOUJIANG("参与抽奖"),
    SWITCH_LIVE("切换直播间")
}
