package com.lolens.modules.build;

import com.lolens.services.cache.AppCache;
import javafx.scene.image.Image;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 图标图片缓存：复用 {@link AppCache} 记录 URL 加载成败状态，另用内存 Map 保存已解码的
 * {@link Image}（JavaFX 图片对象无法 JSON 序列化，不适合直接放进 AppCache）。
 * <ul>
 *   <li>已确认加载失败的 URL（AppCache 中存在 err 标记）直接返回 null，避免重复请求。</li>
 *   <li>成功加载的 URL 记录 ok 标记，同时放入内存 Map 供面板快速复用。</li>
 *   <li>内存 Map 超上限时整体清空（简单策略，图标数量有限，可接受）。</li>
 * </ul>
 */
public final class IconImageCache {

    private static final AppCache APP_CACHE = AppCache.instance();
    private static final String ERR_PREFIX = "icon:err:";
    private static final int MAX_IMAGES = 2000;

    private static final Map<String, Image> IMAGES = new ConcurrentHashMap<>();

    private IconImageCache() {
    }

    /**
     * 获取图标图片：缓存命中直接返回；未命中创建异步加载的 Image 并登记状态。
     * 加载失败（404 等）由调用方通过 errorProperty 监听隐藏节点，或依赖本缓存的下次拒绝。
     *
     * @param url    图标完整 URL
     * @param width  期望宽度
     * @param height 期望高度
     * @return 图片对象；URL 为空或已知加载失败返回 null
     */
    public static Image get(String url, double width, double height) {
        if (url == null || url.isBlank()) {
            return null;
        }
        if (APP_CACHE.get(ERR_PREFIX + url, String.class) != null) {
            return null;
        }
        Image cached = IMAGES.get(url);
        if (cached != null) {
            return cached;
        }
        if (IMAGES.size() >= MAX_IMAGES) {
            IMAGES.clear();
        }
        Image image = new Image(url, width, height, true, true);
        IMAGES.put(url, image);
        image.errorProperty().addListener((obs, ov, err) -> {
            if (Boolean.TRUE.equals(err)) {
                APP_CACHE.put(ERR_PREFIX + url, "1");
                IMAGES.remove(url);
            }
        });
        return image;
    }
}
