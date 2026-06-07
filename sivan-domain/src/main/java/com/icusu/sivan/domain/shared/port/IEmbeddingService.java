package com.icusu.sivan.domain.shared.port;

import java.util.List;

/**
 * Embedding 向量化端口。领域层通过此接口获取文本/多模态向量。
 */
public interface IEmbeddingService {

    /** 检查 Embedding 服务是否已配置可用。 */
    boolean isAvailable();

    /** 将单条文本转为向量，服务未配置时返回 null。 */
    float[] embed(String text);

    /**
     * 将文本 + 图片转为向量（多模态 embedding）。
     *
     * @param text        文本内容
     * @param imageBase64 图片 base64 data URI（格式：data:image/png;base64,...），
     *                    为 null 时等效于 {@link #embed(String)}
     * @return 向量数组，服务未配置时返回 null
     */
    float[] embedWithImage(String text, String imageBase64);

    /**
     * 批量将文本转为向量。相比循环调用 {@link #embed(String)}，减少 HTTP 调用次数。
     *
     * @param texts 文本列表
     * @return 向量列表（顺序与输入一致），某条文本失败时对应位置为 null
     */
    List<float[]> embedBatch(List<String> texts);
}
