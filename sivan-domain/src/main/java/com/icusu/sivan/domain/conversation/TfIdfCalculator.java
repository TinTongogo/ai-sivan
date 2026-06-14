package com.icusu.sivan.domain.conversation;

import java.util.*;

/**
 * 基于字符 bi-gram 的 TF-IDF 计算器，零外部依赖。
 * <p>
 * 将文本按字符重叠二元组（bi-gram）切分作为词项，
 * 适用于中文文本的话题相似度比较，无需引入中文分词库。
 */
public class TfIdfCalculator {

    private final Map<String, Double> idfCache;

    public TfIdfCalculator() {
        this.idfCache = new HashMap<>();
    }

    // ====== 字符 n-gram 提取 ======

    /** 从文本提取字符 bi-gram 及其频次。空白字符被压缩为单个空格。 */
    static Map<String, Integer> extractBigrams(String text) {
        if (text == null || text.isBlank()) return Collections.emptyMap();
        String normalized = text.toLowerCase().replaceAll("\\s+", " ").trim();
        if (normalized.isEmpty()) return Collections.emptyMap();

        Map<String, Integer> ngrams = new HashMap<>();
        for (int i = 0; i < normalized.length() - 1; i++) {
            String bigram = normalized.substring(i, i + 2);
            // 跳过包含空格的 bigram（边界噪声）
            if (bigram.indexOf(' ') >= 0) continue;
            ngrams.merge(bigram, 1, Integer::sum);
        }
        return ngrams;
    }

    // ====== TF-IDF 向量 ======

    /** 计算单条文本的 TF-IDF 向量（相对于给定的语料库统计信息）。 */
    Map<String, Double> tfidf(String text, Map<String, Double> idf) {
        Map<String, Integer> bigrams = extractBigrams(text);
        if (bigrams.isEmpty()) return Collections.emptyMap();

        int totalBigrams = bigrams.values().stream().mapToInt(Integer::intValue).sum();
        Map<String, Double> vector = new HashMap<>();
        for (Map.Entry<String, Integer> entry : bigrams.entrySet()) {
            String term = entry.getKey();
            double tf = (double) entry.getValue() / totalBigrams;
            double idfVal = idf.getOrDefault(term, 0.0);
            vector.put(term, tf * idfVal);
        }
        return vector;
    }

    /** 从一组文本构建 IDF 词典。 */
    Map<String, Double> buildIdf(List<String> documents) {
        if (documents == null || documents.isEmpty()) return idfCache;

        int N = documents.size();
        Map<String, Integer> df = new HashMap<>();

        for (String doc : documents) {
            Map<String, Integer> bigrams = extractBigrams(doc);
            for (String term : bigrams.keySet()) {
                df.merge(term, 1, Integer::sum);
            }
        }

        Map<String, Double> idf = new HashMap<>();
        for (Map.Entry<String, Integer> entry : df.entrySet()) {
            idf.put(entry.getKey(), Math.log((double) N / (entry.getValue() + 1)) + 1.0);
        }
        return idf;
    }

    // ====== 余弦相似度 ======

    /** 计算两个 TF-IDF 向量的余弦相似度。 */
    public double cosineSimilarity(Map<String, Double> v1, Map<String, Double> v2) {
        if (v1.isEmpty() || v2.isEmpty()) return 0.0;

        double dotProduct = 0.0;
        for (Map.Entry<String, Double> entry : v1.entrySet()) {
            Double v2Val = v2.get(entry.getKey());
            if (v2Val != null) {
                dotProduct += entry.getValue() * v2Val;
            }
        }

        double norm1 = Math.sqrt(v1.values().stream().mapToDouble(v -> v * v).sum());
        double norm2 = Math.sqrt(v2.values().stream().mapToDouble(v -> v * v).sum());

        if (norm1 == 0.0 || norm2 == 0.0) return 0.0;
        return dotProduct / (norm1 * norm2);
    }

    // ====== 高级接口 ======

    /**
     * 计算两条文本的 TF-IDF 余弦相似度。
     * 使用传入文本和已有缓存构建 IDF，适合在线场景。
     */
    public double similarity(String text1, String text2) {
        Map<String, Double> idf = buildIdf(List.of(text1, text2));
        Map<String, Double> v1 = tfidf(text1, idf);
        Map<String, Double> v2 = tfidf(text2, idf);
        return cosineSimilarity(v1, v2);
    }

    /**
     * 计算一条文本与一组文本集合的 IDF 下的向量，再与另一个向量比较。
     * 用于新消息 vs 已有话题摘要的比较。
     */
    public double similarityWithCorpus(String newText, List<String> corpus, String compareText) {
        Map<String, Double> idf = buildIdf(corpus);
        Map<String, Double> v1 = tfidf(newText, idf);
        Map<String, Double> v2 = tfidf(compareText, idf);
        return cosineSimilarity(v1, v2);
    }

    /**
     * 从一组文本中提取 Top-K 高频 bi-gram 作为话题标签。
     */
    public List<String> topTerms(List<String> texts, int topK) {
        Map<String, Double> idf = buildIdf(texts);
        Map<String, Double> avgVector = new HashMap<>();
        for (String text : texts) {
            Map<String, Double> vec = tfidf(text, idf);
            for (Map.Entry<String, Double> entry : vec.entrySet()) {
                avgVector.merge(entry.getKey(), entry.getValue(), Double::sum);
            }
        }
        int count = texts.size();
        if (count > 0) {
            avgVector.replaceAll((k, v) -> v / count);
        }

        return avgVector.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(Map.Entry::getKey)
                .toList();
    }
}
