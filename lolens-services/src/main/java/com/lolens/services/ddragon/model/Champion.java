package com.lolens.services.ddragon.model;

import java.util.List;

/**
 * 英雄基础数据（Data Dragon champion.json 精简模型）。
 *
 * @param spellIcons 英雄四个基础技能图标（Data Dragon icon 字段，形如 "spell/LuxLightBinding.png"，
 *                   顺序对应 Q/W/E/R）；champion.json 未提供时为空列表
 */
public record Champion(int id, String key, String name, String title, List<String> tags,
                       List<String> spellIcons) {

    /** 图标相对路径。 */
    public String iconPath() {
        return "img/champion/" + key + ".png";
    }
}
