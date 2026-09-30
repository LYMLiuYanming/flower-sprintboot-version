package org.liuym.flowerv1springboot.vo;

import java.util.List;

/**
 * 立体展台与媒体（J 组）视图：
 * 模型映射走「关键词 → 分类 → 全局兜底」三档命中，matched 字段把命中的那一档带回页面，
 * 页面据此如实写出「通用模型示意」还是「本品专属模型」，不让顾客以为三维视图就是这一束花。
 */
public final class MediaViews {

    private MediaViews() {
    }

    /**
     * @param matchType keyword（按商品名/花材关键词）/ category（按分类名）/ default（全局兜底）
     * @param shared    true = 该品类没有专属模型，借通用花束示意体量与层次
     * @param matched   本次请求实际命中的档位，只有解析结果里才非空
     */
    public record ModelBinding(
            String matchType,
            String matchKey,
            String modelUrl,
            String label,
            String caption,
            String preset,
            Double spinSpeed,
            Boolean shared,
            String matched) {
    }

    /** 映射表全量目录：后台排查与「这个品类到底配了没有」的对照表 */
    public record ModelCatalog(Integer total, List<ModelBinding> bindings, ModelBinding fallback) {
    }

    /**
     * 展台一次解析的结论：modelUrl 为空表示立体视图不可用，页面回落到实拍图 2D 图集
     *
     * @param reason 没有模型时的原因说明，页面直接展示，比「加载失败」更诚实
     */
    public record Stage(String modelUrl, String label, String caption, String preset, Double spinSpeed,
                        Boolean shared, String matched, String reason) {
    }

    /**
     * 缩略图请求的校验结果（/img/thumb 的解析口径，纯函数便于单测）
     *
     * @param passthrough true = 源文件是矢量图或不比请求宽度大，原样吐出不再重采样
     */
    public record ThumbRequest(String path, Integer width, Integer height, Boolean passthrough) {
    }
}
